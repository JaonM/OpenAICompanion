import Foundation
import CryptoKit
import MLX
import MLXLLM
import MLXLMCommon
import MLXGuidedGeneration
import Tokenizers

typealias ChunkCallback = @convention(c) (UnsafePointer<CChar>?, UnsafeMutableRawPointer?) -> Void

private final class ChunkEmitter: @unchecked Sendable {
    let callback: ChunkCallback
    let context: UnsafeMutableRawPointer?
    init(_ callback: @escaping ChunkCallback, _ context: UnsafeMutableRawPointer?) {
        self.callback = callback; self.context = context
    }
    func send(_ object: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: object)
        String(decoding: data, as: UTF8.self).withCString { callback($0, context) }
    }
    func delta(_ text: String, field: String = "content") throws {
        if !text.isEmpty { try send(["choices": [["delta": [field: text]]]]) }
    }
}

private struct LocalTokenizer: MLXLMCommon.Tokenizer, @unchecked Sendable {
    let upstream: any Tokenizers.Tokenizer
    func encode(text: String, addSpecialTokens: Bool) -> [Int] { upstream.encode(text: text, addSpecialTokens: addSpecialTokens) }
    func decode(tokenIds: [Int], skipSpecialTokens: Bool) -> String { upstream.decode(tokens: tokenIds, skipSpecialTokens: skipSpecialTokens) }
    func convertTokenToId(_ token: String) -> Int? { upstream.convertTokenToId(token) }
    func convertIdToToken(_ id: Int) -> String? { upstream.convertIdToToken(id) }
    var bosToken: String? { upstream.bosToken }
    var eosToken: String? { upstream.eosToken }
    var unknownToken: String? { upstream.unknownToken }
    func applyChatTemplate(messages: [[String: any Sendable]], tools: [[String: any Sendable]]?, additionalContext: [String: any Sendable]?) throws -> [Int] {
        try upstream.applyChatTemplate(messages: messages, tools: tools, additionalContext: additionalContext)
    }
}
private struct LocalTokenizerLoader: TokenizerLoader {
    func load(from directory: URL) async throws -> any MLXLMCommon.Tokenizer {
        LocalTokenizer(upstream: try await AutoTokenizer.from(modelFolder: directory))
    }
}

/// Apply answer or native required-call constraints after free-form reasoning.
private final class AnswerGrammar: LogitProcessor {
    let constraint: GrammarConstraint
    let endThinking: Int
    var answering = false
    var terminated = false
    var failure: Error?
    init(_ constraint: GrammarConstraint, endThinking: Int) { self.constraint = constraint; self.endThinking = endThinking }
    func prompt(_ prompt: MLXArray) {}
    func process(logits: MLXArray) -> MLXArray {
        guard answering && !terminated else { return logits }
        do {
            let mask = try constraint.computeMask()
            guard mask.needsApply else { return logits }
            let values: [Float] = (0..<logits.dim(-1)).map { index in
                let word = index / 32
                return word < mask.mask.count && (UInt32(bitPattern: mask.mask[word]) & (1 << UInt32(index % 32))) != 0 ? 0 : -1e9
            }
            return logits + MLXArray(values)
        } catch { failure = error; return logits }
    }
    func didSample(token: MLXArray) {
        let id = token.item(Int.self)
        if terminated { return }
        if !answering { if id == endThinking { answering = true }; return }
        do { terminated = try constraint.commitToken(Int32(id)).isTerminated } catch { failure = error }
    }
    func copy() -> AnswerGrammar {
        do {
            let copy = AnswerGrammar(try constraint.clone(), endThinking: endThinking)
            copy.answering = answering; copy.terminated = terminated; copy.failure = failure
            return copy
        }
        catch { let copy = AnswerGrammar(constraint, endThinking: endThinking); copy.failure = error; return copy }
    }
}

// Use xgrammar's JSON Schema compiler for its optimized JSON string matcher.
// The llama.cpp GBNF string production becomes progressively expensive in xgrammar.
private func responseSchema(request: [String: Any]) throws -> String? {
    guard let format = request["response_format"] as? [String: Any] else { return nil }
    guard format["type"] as? String == "json_schema",
          let specification = format["json_schema"] as? [String: Any],
          let schema = specification["schema"] as? [String: Any] else {
        throw MLXFailure.message("MLX 结构化输出 Schema 无效")
    }
    return String(decoding: try JSONSerialization.data(withJSONObject: schema, options: [.sortedKeys, .withoutEscapingSlashes]), as: UTF8.self)
}

/// Express current discovery/route limits as standard tool Schema constraints.
private func nativeTools(_ request: [String: Any]) throws -> [[String: Any]] {
    guard let raw = request["tools"] else { return [] }
    guard var tools = raw as? [[String: Any]] else { throw MLXFailure.message("MLX tools 无效") }
    let messages = request["messages"] as? [[String: Any]] ?? []
    let start = messages.lastIndex { $0["role"] as? String == "user" } ?? messages.count
    let current = messages.dropFirst(start)
    func result(_ name: String) -> [String: Any]? {
        guard let text = current.last(where: { $0["role"] as? String == "tool" && $0["name"] as? String == name })?["content"] as? String else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
    }
    let discovery = result("list_execution_devices")?["devices"] as? [[String: Any]]
    let resources = discovery.map { Array(Set($0.flatMap { $0["resources"] as? [String] ?? [] })).sorted() }
    let route = result("route_task")
    for index in tools.indices {
        guard var function = tools[index]["function"] as? [String: Any],
              var parameters = function["parameters"] as? [String: Any],
              var properties = parameters["properties"] as? [String: Any] else { continue }
        if function["name"] as? String == "route_task", let resources,
           var refs = properties["resource_refs"] as? [String: Any] {
            if resources.isEmpty { refs["maxItems"] = 0 }
            else { refs["items"] = ["type": "string", "enum": resources] }
            properties["resource_refs"] = refs
        }
        if function["name"] as? String == "delegate_to_agent", route?["decision"] as? String == "REMOTE",
           let agent = route?["agent_id"] as? String, !agent.isEmpty,
           var target = properties["agent_id"] as? [String: Any] {
            target["const"] = agent
            properties["agent_id"] = target
        }
        parameters["properties"] = properties; function["parameters"] = parameters; tools[index]["function"] = function
    }
    return tools
}

/// Constrain required calls in a dialect accepted by the native SDK parser.
private func requiredToolTag(_ tools: [[String: Any]], format: ToolCallFormat) throws -> String? {
    guard format == .qwen35 || format == .xmlFunction || format == .json else { return nil }
    let functions: [[String: Any]] = try tools.map { tool in
        guard let function = tool["function"] as? [String: Any], let name = function["name"] as? String,
              let parameters = function["parameters"] as? [String: Any], parameters["type"] as? String == "object" else {
            throw MLXFailure.message("MLX 工具 Schema 无效")
        }
        if format != .xmlFunction {
            return ["type": "object", "properties": ["name": ["type": "string", "const": name], "arguments": parameters],
                    "required": ["name", "arguments"], "additionalProperties": false]
        }
        // The SDK XML compiler emits invalid EBNF for an empty closed object.
        let empty = (parameters["properties"] as? [String: Any])?.isEmpty == true && parameters["additionalProperties"] as? Bool == false
        let body: [String: Any] = empty ? ["type": "const_string", "value": "\n"] :
            ["type": "qwen_xml_parameter", "json_schema": parameters]
        return ["type": "tag", "begin": "<function=\(name)>\n", "end": "</function>\n", "content": body]
    }
    let body: [String: Any] = format == .xmlFunction ? ["type": "or", "elements": functions] :
        ["type": "json_schema", "json_schema": ["anyOf": functions]]
    let tag: [String: Any] = ["format": ["type": "tag", "begin": "<tool_call>\n", "end": "</tool_call>", "content": body]]
    return String(decoding: try JSONSerialization.data(withJSONObject: tag, options: [.sortedKeys]), as: UTF8.self)
}

/// Bridge Foundation JSON into the SDK's Sendable message/tool dictionaries.
private func nativeJSON(_ value: Any) throws -> any Sendable {
    switch value {
    case let value as String: return value
    case let value as NSNumber:
        if CFGetTypeID(value) == CFBooleanGetTypeID() { return value.boolValue }
        if String(cString: value.objCType) == "d" || String(cString: value.objCType) == "f" { return value.doubleValue }
        return value.int64Value
    case is NSNull: return Optional<String>.none
    case let value as [String: Any]: return try value.mapValues(nativeJSON)
    case let value as [Any]: return try value.map(nativeJSON)
    default: throw MLXFailure.message("MLX 请求包含不支持的 JSON 值")
    }
}

/// UTF-16 offsets preserve streamed combining characters and emoji sequences.
private func answerSuffix(_ answer: String, after previous: String) throws -> String {
    guard answer.utf16.starts(with: previous.utf16) else {
        throw MLXFailure.message("MLX 正文流不连续")
    }
    return (answer as NSString).substring(from: previous.utf16.count)
}

private enum MLXFailure: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

private final class LocalMLX: @unchecked Sendable {
    static let shared = LocalMLX()
    private let lock = NSLock()
    private var active: Task<Void, Never>?
    private var container: ModelContainer?
    private var loadedPath: String?
    private var grammarTokenizer: GrammarTokenizer?

    // Kotlin calls this on its serial background model gate, never the main thread.
    func run(_ operation: @escaping @Sendable () async throws -> Void) -> UnsafeMutablePointer<CChar>? {
        let done = DispatchSemaphore(value: 0)
        let result = ResultBox()
        let task = Task.detached(priority: .userInitiated) {
            do { try await operation() } catch { result.error = error is GrammarError ? String(describing: error) : error.localizedDescription }
            done.signal()
        }
        lock.lock(); active = task; lock.unlock()
        done.wait()
        lock.lock(); active = nil; lock.unlock()
        return result.error.flatMap { strdup($0) }
    }
    func cancel() { lock.lock(); let task = active; lock.unlock(); task?.cancel() }
    func unload() { container = nil; grammarTokenizer = nil; loadedPath = nil; MLX.Memory.clearCache() }

    func validateImportedModel(path: String) async throws {
        unload()
        defer { Stream.defaultStream.synchronize(); unload() }
        MLX.Memory.cacheLimit = 128 * 1024 * 1024
        _ = try await LLMModelFactory.shared.loadContainer(from: URL(fileURLWithPath: path), using: LocalTokenizerLoader())
    }

    func generate(path: String, requestJSON: String, maxTokens: Int, emit: ChunkEmitter) async throws {
        if loadedPath != path {
            unload()
            MLX.Memory.cacheLimit = 128 * 1024 * 1024
            container = try await LLMModelFactory.shared.loadContainer(from: URL(fileURLWithPath: path), using: LocalTokenizerLoader())
            loadedPath = path
            logMLX("model loaded: active=\(MLX.Memory.activeMemory)")
        }
        guard let container,
              let request = try JSONSerialization.jsonObject(with: Data(requestJSON.utf8)) as? [String: Any],
              let rawMessages = request["messages"] as? [Any] else { throw MLXFailure.message("MLX 请求缺少 messages") }
        guard let normalized = OCMLXPromptMessages(rawMessages), !normalized.isEmpty else {
            throw MLXFailure.message("MLX 请求包含无效消息或非文本内容")
        }
        var messages = try normalized.map { try $0.mapValues(nativeJSON) }
        let toolSchemas = try nativeTools(request)
        let tools = try toolSchemas.map { try $0.mapValues(nativeJSON) }
        let schema = try responseSchema(request: request)
        // The template owns the tool protocol; these instructions only express request policy.
        var instructions: [String] = []
        if let schema, !tools.isEmpty {
            instructions.append("When returning a final answer rather than calling a tool, output only JSON matching this schema: \(schema)")
        }
        if schema == nil {
            instructions.append("Follow the latest user's requested answer format exactly. Do not add headings, status labels or explanatory notes when the user asks for only a number or a specific string. Older assistant answer formats and task failures do not define this new request.")
        }
        let requiredTool = request["tool_choice"] as? String == "required"
        if requiredTool && tools.isEmpty { throw MLXFailure.message("MLX 没有可用的必需工具") }
        if requiredTool {
            instructions.append("A tool call is required for this turn. Call one of the supplied functions using its declared parameters; do not replace the call with a textual answer.")
        }
        if !instructions.isEmpty {
            let instruction = "\n" + instructions.joined(separator: "\n")
            if messages.first?["role"] as? String == "system" {
                messages[0]["content"] = (messages[0]["content"] as? String ?? "") + instruction
            } else { messages.insert(["role": "system", "content": instruction], at: 0) }
        }
        let configurationData = try Data(contentsOf: URL(fileURLWithPath: path).appendingPathComponent("config.json"))
        let modelType = (try JSONSerialization.jsonObject(with: configurationData) as? [String: Any])?["model_type"] as? String ?? ""
        let folder = URL(fileURLWithPath: path)
        let template = (try? String(contentsOf: folder.appendingPathComponent("chat_template.jinja"), encoding: .utf8)) ??
            ((try? JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("tokenizer_config.json")))) as? [String: Any])?["chat_template"] as? String ?? ""
        let structured = schema != nil
        let usesThinking = !structured && ["qwen3", "qwen3_5", "qwen3_5_text"].contains(modelType) && template.contains("<think>")
        let promptMessages = messages
        try await container.perform { context in
            let input = try await context.processor.prepare(input: UserInput(messages: promptMessages, tools: tools.isEmpty ? nil : tools, additionalContext: ["enable_thinking": usesThinking]))
            logMLX("prompt tokens=\(input.text.tokens.size), active=\(MLX.Memory.activeMemory)")
            let answerLimit = min(max(maxTokens, 1), 512)
            if input.text.tokens.size + 256 + answerLimit + 32 > 8192 { throw MLXFailure.message("LOCAL_MODEL_CONTEXT_EXCEEDED") }
            let parameters = GenerateParameters(maxTokens: (usesThinking ? 256 : 0) + answerLimit + 32, temperature: structured || requiredTool ? 0 : 0.6, topP: 0.95, topK: 20, presencePenalty: 1.5, presenceContextSize: 1024)
            let thinking = usesThinking ? try ThinkingBudgetProcessor(configuration: ThinkingBudgetConfiguration(maximumTokenCount: 256, minimumAnswerTokenCount: answerLimit, transitionOverride: .immediate), reasoning: .thinkTagsWithEnableThinking, tokenizer: context.tokenizer) : nil
            var processors: [any LogitProcessor] = []
            if let penalty = parameters.processor() { processors.append(penalty) }
            if let thinking { processors.append(thinking) }
            let format: ToolCallFormat = ["qwen3_5", "qwen3_5_text"].contains(modelType) ? .qwen35 : (context.configuration.toolCallFormat ?? .json)
            let toolTag = requiredTool ? try requiredToolTag(toolSchemas, format: format) : nil
            var answerGrammar: AnswerGrammar?
            if (schema != nil && tools.isEmpty) || toolTag != nil {
                if self.grammarTokenizer == nil {
                    let vocab = TokenizerVocabExtractor.extractForGrammar(from: context.tokenizer)
                    self.grammarTokenizer = try GrammarTokenizer(vocab: vocab.vocab, vocabType: vocab.vocabType, eosTokenId: Int32(context.tokenizer.eosTokenId ?? 0))
                }
                guard let grammarTokenizer = self.grammarTokenizer else { throw MLXFailure.message("MLX 无法准备回答约束") }
                let close = context.tokenizer.convertTokenToId("</think>") ?? -1
                let constraint: GrammarConstraint
                if let toolTag { constraint = try GrammarConstraint(tokenizer: grammarTokenizer, structuralTag: toolTag) }
                else { constraint = try GrammarConstraint(tokenizer: grammarTokenizer, jsonSchema: schema!) }
                answerGrammar = AnswerGrammar(constraint, endThinking: close)
                answerGrammar?.answering = !usesThinking
                processors.append(answerGrammar!)
            }
            logMLX("grammar ready: active=\(MLX.Memory.activeMemory)")
            // Bound in-flight prompt graphs on iPhone; desktop-oriented pipelining can exceed its memory budget.
            let prefill = PrefillParameters(stepSize: 128, progress: { _, _ in
                Stream.defaultStream.synchronize()
                MLX.Memory.clearCache()
            })
            var iterator = try TokenIterator(input: input, model: context.model, processor: ChainedLogitProcessor(processors: processors), sampler: parameters.sampler(), prefill: prefill, maxTokens: parameters.maxTokens)
            // The iterator prefetches on the GPU; drain it before cancellation or engine unload returns.
            defer { Stream.defaultStream.synchronize() }
            logMLX("prefill ready: active=\(MLX.Memory.activeMemory), peak=\(MLX.Memory.peakMemory)")
            var decoder = NaiveStreamingDetokenizer(tokenizer: context.tokenizer)
            let stopIDs = Set([context.tokenizer.eosTokenId, context.tokenizer.convertTokenToId("<|im_end|>")].compactMap { $0 })
            let toolParser = tools.isEmpty ? nil : ToolCallProcessor(format: format, tools: tools,
                toolCallPolicy: .init(validation: .strict))
            var output = "", emittedReasoning = "", consumedAnswer = "", answerText = ""
            var thinkingClosed = !usesThinking, answerTokens = 0, sampledTokens = 0
            func consumeAnswer(_ answer: String) throws {
                let chunk = try answerSuffix(answer, after: consumedAnswer)
                consumedAnswer = answer
                if let text = toolParser?.processChunk(chunk) ?? (toolParser == nil ? chunk : nil) {
                    answerText += text
                    if !structured { try emit.delta(text) }
                }
            }
            while true {
                try Task.checkCancellation()
                guard let token = iterator.next() else { break }
                sampledTokens += 1
                if sampledTokens % 64 == 0 { logMLX("tokens=\(sampledTokens), active=\(MLX.Memory.activeMemory), peak=\(MLX.Memory.peakMemory)") }
                if stopIDs.contains(token) { break }
                if let failure = answerGrammar?.failure { throw failure }
                decoder.append(token: token)
                if let chunk = decoder.next() { output += chunk }
                if thinkingClosed { answerTokens += 1 }
                else if output.contains("</think>") { thinkingClosed = true }
                let reasoning = usesThinking ? OCLlamaStreamingReasoningText(output) : ""
                if reasoning.utf16.count > emittedReasoning.utf16.count {
                    try emit.delta((reasoning as NSString).substring(from: emittedReasoning.utf16.count), field: "reasoning_content")
                    emittedReasoning = reasoning
                }
                if let parts = usesThinking ? OCLlamaSplitThinkingResponse(output) : ["reasoning": "", "text": output] {
                    try consumeAnswer(parts["text"] ?? "")
                }
                if answerGrammar?.terminated == true || answerTokens >= answerLimit { break }
            }
            try Task.checkCancellation()
            if let failure = answerGrammar?.failure { throw failure }
            guard let parts = usesThinking ? OCLlamaSplitThinkingResponse(output) : ["reasoning": "", "text": output] else { throw MLXFailure.message("MLX 思考未完成") }
            let reasoning = parts["reasoning"] ?? ""
            if reasoning.utf16.count > emittedReasoning.utf16.count {
                try emit.delta((reasoning as NSString).substring(from: emittedReasoning.utf16.count), field: "reasoning_content")
            }
            try consumeAnswer(parts["text"] ?? "")
            if let tail = toolParser?.processEOS(returnBufferedText: true) {
                answerText += tail
                if !structured { try emit.delta(tail) }
            }
            if let toolParser, !toolParser.rejectedToolCalls.isEmpty {
                let reasons = toolParser.rejectedToolCalls.map { $0.reason.rawValue + ($0.detail.map { ": " + $0 } ?? "") }.joined(separator: ", ")
                throw MLXFailure.message("MLX 工具调用被拒绝：\(reasons)")
            }
            let calls = toolParser?.toolCalls ?? []
            if !calls.isEmpty {
                let wireCalls: [[String: Any]] = try calls.map { call in
                    let arguments = String(decoding: try JSONEncoder().encode(call.function.arguments), as: UTF8.self)
                    return ["id": call.id ?? "call_" + UUID().uuidString, "type": "function",
                        "function": ["name": call.function.name, "arguments": arguments]]
                }
                try emit.send(["choices": [["message": ["tool_calls": wireCalls]]]])
            } else {
                if requiredTool { throw MLXFailure.message("MLX 未生成本轮要求的工具调用") }
                if structured {
                    guard (try? JSONSerialization.jsonObject(with: Data(answerText.utf8), options: [.fragmentsAllowed])) != nil else {
                        throw MLXFailure.message("MLX 结构化回答未完成")
                    }
                    try emit.delta(answerText)
                } else if answerText.isEmpty { throw MLXFailure.message("MLX 没有返回正文") }
            }

        }
    }
}
private final class ResultBox: @unchecked Sendable { var error: String? }

@_cdecl("oc_mlx_generate")
func ocMLXGenerate(_ path: UnsafePointer<CChar>?, _ request: UnsafePointer<CChar>?, _ maxTokens: Int32, _ callback: ChunkCallback?, _ context: UnsafeMutableRawPointer?) -> UnsafeMutablePointer<CChar>? {
    guard let path, let request, let callback else { return strdup("MLX 请求无效") }
    let modelPath = String(cString: path), requestJSON = String(cString: request), emit = ChunkEmitter(callback, context)
    return LocalMLX.shared.run { try await LocalMLX.shared.generate(path: modelPath, requestJSON: requestJSON, maxTokens: Int(maxTokens), emit: emit) }
}
@_cdecl("oc_mlx_cancel") func ocMLXCancel() { LocalMLX.shared.cancel() }
@_cdecl("oc_mlx_unload") func ocMLXUnload() { LocalMLX.shared.unload() }

private struct MLXModelFile: Decodable {
    let file: String
    let bytes: Int64
    let sha256: String?
    let blobId: String?
}

private func downloadMLXModel(to path: String, repository: String, revision: String, manifest: String) async throws {
    let files = try JSONDecoder().decode([MLXModelFile].self, from: Data(manifest.utf8))
    guard repository.range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$", options: .regularExpression) != nil,
          revision.range(of: "^[a-f0-9]{40}$", options: .regularExpression) != nil,
          !files.isEmpty, files.count <= 512, Set(files.map(\.file)).count == files.count else { throw MLXFailure.message("模型文件清单无效") }
    let destination = URL(fileURLWithPath: path, isDirectory: true)
    let staging = destination.deletingLastPathComponent().appendingPathComponent(".mlx-download-" + UUID().uuidString)
    let manager = FileManager.default
    try manager.createDirectory(at: staging, withIntermediateDirectories: true)
    defer { try? manager.removeItem(at: staging) }
    for file in files {
        try Task.checkCancellation()
        guard file.bytes > 0, file.file.count <= 200,
              file.file.split(separator: "/", omittingEmptySubsequences: false).allSatisfy({ $0 != "." && $0 != ".." && $0.range(of: "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil }) else { throw MLXFailure.message("模型文件路径无效") }
        let url = URL(string: "https://huggingface.co/\(repository)/resolve/\(revision)/\(file.file)")!
        let (temporary, response) = try await URLSession.shared.download(from: url)
        defer { try? manager.removeItem(at: temporary) }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              (try manager.attributesOfItem(atPath: temporary.path)[.size] as? NSNumber)?.int64Value == file.bytes else { throw MLXFailure.message("模型文件下载失败：\(file.file)") }
        let handle = try FileHandle(forReadingFrom: temporary)
        defer { try? handle.close() }
        var hash = SHA256(), gitHash = Insecure.SHA1()
        gitHash.update(data: Data("blob \(file.bytes)\0".utf8))
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
            try Task.checkCancellation(); hash.update(data: data); gitHash.update(data: data)
        }
        let digest = hash.finalize().map({ String(format: "%02x", $0) }).joined()
        let gitDigest = gitHash.finalize().map({ String(format: "%02x", $0) }).joined()
        let valid = !(file.sha256 ?? "").isEmpty ? digest == file.sha256 : gitDigest == file.blobId
        guard valid else { throw MLXFailure.message("模型文件校验失败：\(file.file)") }
        let target = staging.appendingPathComponent(file.file)
        try manager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try manager.moveItem(at: temporary, to: target)
    }
    if manager.fileExists(atPath: destination.path) { _ = try manager.replaceItemAt(destination, withItemAt: staging) }
    else { try manager.moveItem(at: staging, to: destination) }
}
@_cdecl("oc_mlx_download_manifest")
func ocMLXDownloadManifest(_ path: UnsafePointer<CChar>?, _ repository: UnsafePointer<CChar>?, _ revision: UnsafePointer<CChar>?, _ manifest: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let path, let repository, let revision, let manifest else { return strdup("MLX 模型下载参数无效") }
    let directory = String(cString: path), repo = String(cString: repository), sha = String(cString: revision), files = String(cString: manifest)
    return LocalMLX.shared.run { try await downloadMLXModel(to: directory, repository: repo, revision: sha, manifest: files) }
}

// Local import never resolves remote files. Only safe model files are copied.
@_cdecl("oc_mlx_import_directory")
func ocMLXImportDirectory(_ sourcePath: UnsafePointer<CChar>?, _ modelsPath: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let sourcePath, let modelsPath else { return strdup("MLX 导入路径无效") }
    let source = URL(fileURLWithPath: String(cString: sourcePath), isDirectory: true)
    let base = URL(fileURLWithPath: String(cString: modelsPath), isDirectory: true)
    let result = ResultBox()
    if let failure = LocalMLX.shared.run({
        let manager = FileManager.default
        let urls = try manager.contentsOfDirectory(at: source, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        let files = try urls.filter { url in
            let name = url.lastPathComponent
            return name.hasSuffix(".safetensors") || name.hasSuffix(".json") || ["tokenizer.model", "merges.txt", "vocab.txt", "chat_template.jinja"].contains(name)
        }.map { url -> (URL, Int64) in
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true,
                  url.lastPathComponent.range(of: "^[A-Za-z0-9_.-]{1,200}$", options: .regularExpression) != nil,
                  let size = values.fileSize, size > 0 else { throw MLXFailure.message("模型文件不完整或包含不安全路径") }
            return (url, Int64(size))
        }
        let names = Set(files.map { $0.0.lastPathComponent })
        guard files.count <= 512, names.contains("config.json"), names.contains("tokenizer_config.json"),
              names.contains("tokenizer.json") || names.contains("tokenizer.model"),
              names.contains(where: { $0.hasSuffix(".safetensors") }) else { throw MLXFailure.message("请选择完整 MLX 模型文件夹，包含配置、tokenizer 和 safetensors 权重") }
        let config = try JSONSerialization.jsonObject(with: Data(contentsOf: source.appendingPathComponent("config.json"))) as? [String: Any]
        guard let family = config?["model_type"] as? String,
              ["qwen3", "qwen3_5", "qwen3_5_text", "qwen2", "llama", "mistral", "gemma", "gemma2", "gemma3_text", "phi3"].contains(family) else { throw MLXFailure.message("当前 MLX 文本引擎不支持这个模型架构") }
        let bytes = files.reduce(Int64(0)) { $0 + $1.1 }
        let weightBytes = files.filter { $0.0.pathExtension == "safetensors" }.reduce(Int64(0)) { $0 + $1.1 }
        let memoryGB = max(4, Int(ceil((Double(weightBytes) * 1.5 + 2_000_000_000) / 1_000_000_000)))
        guard ProcessInfo.processInfo.physicalMemory >= UInt64(memoryGB) * 1_000_000_000 else { throw MLXFailure.message("此模型建议至少 \(memoryGB) GB 内存") }
        let available = try manager.attributesOfFileSystem(forPath: NSHomeDirectory())[.systemFreeSize] as? NSNumber
        guard let available, available.int64Value >= bytes * 2 + 1_000_000_000 else { throw MLXFailure.message("模型导入暂存空间不足") }
        let id = UUID().uuidString
        let staging = base.appendingPathComponent(".import-" + id)
        let destination = base.appendingPathComponent("local/" + id)
        try manager.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: staging) }
        for (url, _) in files {
            try Task.checkCancellation()
            try manager.copyItem(at: url, to: staging.appendingPathComponent(url.lastPathComponent))
        }
        logMLX("local import copied: files=\(files.count), bytes=\(bytes)")
        try await LocalMLX.shared.validateImportedModel(path: staging.path)
        logMLX("local import validated")
        try manager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try manager.moveItem(at: staging, to: destination)
        let manifest: [[String: Any]] = files.map { ["file": $0.0.lastPathComponent, "bytes": $0.1] }
        let descriptor: [String: Any] = ["id": "local-mlx-" + id, "title": source.lastPathComponent + "（本地导入）", "engine": "MLX", "bytes": bytes, "memoryGB": memoryGB, "repository": "local/" + id, "revision": "local", "files": manifest]
        result.error = String(data: try JSONSerialization.data(withJSONObject: descriptor), encoding: .utf8)
        logMLX("local import installed")
    }) { return failure }
    return result.error.flatMap { strdup($0) }
}

private func logMLX(_ message: @autoclosure () -> String) {
    if ProcessInfo.processInfo.environment["COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS"] == "1" { NSLog("MLX: %@", message()) }
}

// GGUF downloads use a temporary file and verify the pinned artifact before installation.
@_cdecl("oc_model_download")
func ocModelDownload(_ rawURL: UnsafePointer<CChar>?, _ rawPath: UnsafePointer<CChar>?, _ rawHash: UnsafePointer<CChar>?, _ bytes: Int64) -> UnsafeMutablePointer<CChar>? {
    guard let rawURL, let rawPath, let rawHash,
          let url = URL(string: String(cString: rawURL)), url.scheme == "https", url.host == "huggingface.co" else { return strdup("模型下载参数无效") }
    let destination = URL(fileURLWithPath: String(cString: rawPath)), expectedHash = String(cString: rawHash)
    return LocalMLX.shared.run {
        let manager = FileManager.default
        let (temporary, response) = try await URLSession.shared.download(from: url)
        defer { try? manager.removeItem(at: temporary) }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              (try manager.attributesOfItem(atPath: temporary.path)[.size] as? NSNumber)?.int64Value == bytes else { throw MLXFailure.message("模型下载失败或文件大小不匹配") }
        let handle = try FileHandle(forReadingFrom: temporary)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }
        guard hash.finalize().map({ String(format: "%02x", $0) }).joined() == expectedHash else { throw MLXFailure.message("模型 SHA256 校验失败") }
        try manager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        if manager.fileExists(atPath: destination.path) { _ = try manager.replaceItemAt(destination, withItemAt: temporary) }
        else { try manager.moveItem(at: temporary, to: destination) }
    }
}
