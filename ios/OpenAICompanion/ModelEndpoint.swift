import Foundation
import OpenAICompanionShared

struct ModelSettings: Equatable, Sendable {
    var endpoint: String
    var model: String
    var apiKey: String

    static func load() -> Self {
        let defaults = CompanionModelDefaults()
        #if targetEnvironment(simulator)
        let initialEndpoint = defaults.desktopEndpoint
        #else
        let initialEndpoint = ""
        #endif
        return Self(
            endpoint: UserDefaults.standard.string(forKey: "modelEndpoint") ?? initialEndpoint,
            model: UserDefaults.standard.string(forKey: "modelName") ?? defaults.modelName,
            apiKey: ""
        )
    }
}

final class IOSModelEndpoint: ModelServeCallback, @unchecked Sendable {
    private let lock = NSLock()
    private var settings = ModelSettings.load()
    private var active: Task<Void, Error>?
    private var lastFailure: String?

    func currentSettings() -> ModelSettings {
        lock.lock()
        defer { lock.unlock() }
        return settings
    }

    func save(_ value: ModelSettings) {
        let normalized = ModelSettings(
            endpoint: value.endpoint.trimmingCharacters(in: .whitespacesAndNewlines),
            model: value.model.trimmingCharacters(in: .whitespacesAndNewlines),
            apiKey: value.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        lock.lock()
        settings = normalized
        lock.unlock()
        // The API key deliberately remains process-only, like the macOS app.
        UserDefaults.standard.set(normalized.endpoint, forKey: "modelEndpoint")
        UserDefaults.standard.set(normalized.model, forKey: "modelName")
    }

    func cancel() {
        lock.lock()
        lastFailure = "生成已取消"
        let task = active
        lock.unlock()
        task?.cancel()
    }

    func latestError() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return lastFailure
    }

    func complete(requestJson: String, callback: ModelStreamCallback) async throws {
        let configuration = currentSettings()
        let work = Task<Void, Error> {
            try await stream(requestJson: requestJson, callback: callback, settings: configuration)
        }
        lock.lock()
        lastFailure = nil
        active = work
        lock.unlock()
        defer {
            lock.lock()
            active = nil
            lock.unlock()
        }
        do {
            try await work.value
        } catch {
            let message = work.isCancelled ? "生成已取消" : error.localizedDescription
            lock.lock()
            lastFailure = message
            lock.unlock()
            // UniFFI 0.29 foreign flat errors lose their detail in Rust. Deliver
            // the error as a provider-neutral stream chunk, as on macOS.
            let payload = (try? JSONSerialization.data(withJSONObject: ["error": message]))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "{\"error\":\"模型请求失败\"}"
            callback.onChunk(chunkJson: payload)
        }
    }

    func status() async -> String {
        let configuration = currentSettings()
        guard let url = URL(string: configuration.endpoint),
              let host = url.host, !host.isEmpty else { return "请配置模型地址" }
        guard !configuration.model.isEmpty else { return "请配置模型名称" }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.path = "/api/tags"
        components?.query = nil
        guard let tagsURL = components?.url else { return "模型地址无效" }
        var request = URLRequest(url: tagsURL)
        request.timeoutInterval = 3
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode),
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let models = object["models"] as? [[String: Any]] else {
                return "模型服务已配置"
            }
            let installed = models.contains { item in
                (item["name"] as? String) == configuration.model ||
                (item["model"] as? String) == configuration.model
            }
            return installed ? "本地模型已就绪" : "模型尚未下载"
        } catch {
            return "模型服务未连接"
        }
    }

    private func stream(requestJson: String, callback: ModelStreamCallback, settings: ModelSettings) async throws {
        let defaults = CompanionModelDefaults()
        if let problem = defaults.validationError(endpoint: settings.endpoint, model: settings.model) {
            throw EndpointError.invalidConfiguration(problem)
        }
        guard let url = URL(string: settings.endpoint),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else {
            throw EndpointError.invalidConfiguration("模型接口地址无效")
        }
        guard var object = try JSONSerialization.jsonObject(with: Data(requestJson.utf8)) as? [String: Any] else {
            throw EndpointError.invalidConfiguration("Harness 模型请求格式无效")
        }
        if let tools = object["tools"] as? [Any], tools.isEmpty { object.removeValue(forKey: "tools") }
        object["model"] = settings.model
        object["stream"] = true
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30 * 60
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !settings.apiKey.isEmpty {
            request.setValue("Bearer \(settings.apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: object)
        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw EndpointError.invalidResponse("模型服务没有返回 HTTP 响应")
        }
        guard (200...299).contains(http.statusCode) else {
            var details = ""
            for try await line in bytes.lines {
                details += line
                if details.count >= 300 { break }
            }
            throw EndpointError.invalidResponse("模型接口返回 HTTP \(http.statusCode)：\(details.prefix(300))")
        }
        if http.value(forHTTPHeaderField: "Content-Type")?.localizedCaseInsensitiveContains("text/event-stream") == true {
            var dataLines: [String] = []
            for try await line in bytes.lines {
                try Task.checkCancellation()
                if line.isEmpty {
                    let payload = dataLines.joined(separator: "\n")
                    dataLines.removeAll(keepingCapacity: true)
                    if payload == "[DONE]" { return }
                    if !payload.isEmpty { callback.onChunk(chunkJson: payload) }
                } else if line.hasPrefix("data:") {
                    dataLines.append(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces))
                }
            }
            if !dataLines.isEmpty {
                let payload = dataLines.joined(separator: "\n")
                if payload != "[DONE]" { callback.onChunk(chunkJson: payload) }
            }
        } else {
            var body = Data()
            for try await byte in bytes {
                try Task.checkCancellation()
                body.append(byte)
            }
            callback.onChunk(chunkJson: String(decoding: body, as: UTF8.self))
        }
    }
}

private enum EndpointError: LocalizedError {
    case invalidConfiguration(String)
    case invalidResponse(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let value), .invalidResponse(let value): value
        }
    }
}
