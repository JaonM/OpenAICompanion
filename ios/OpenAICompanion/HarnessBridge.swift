import Foundation
import OpenAICompanionShared

struct ChatSession: Identifiable, Hashable, Sendable {
    let id: Int64
    let preview: String

    var title: String { preview.isEmpty ? "新会话" : preview }
}

struct ChatMessage: Identifiable, Sendable {
    let id = UUID()
    let role: String
    let content: String
}

enum LiveEvent: Sendable {
    case reasoning(String)
    case text(String)
    case completed(String)
    case error(String)
}

final class LiveEventSink: AgentEventSink, @unchecked Sendable {
    private let receive: @Sendable (LiveEvent) -> Void

    init(receive: @escaping @Sendable (LiveEvent) -> Void) {
        self.receive = receive
    }

    func onReasoningDelta(text: String) { receive(.reasoning(text)) }
    func onTextDelta(text: String) { receive(.text(text)) }
    func onCompleted(finalText: String) { receive(.completed(finalText)) }
    func onError(errorJson: String) { receive(.error(errorJson)) }
}

final class HarnessBridge: @unchecked Sendable {
    let modelEndpoint = IOSModelEndpoint()
    private let codec = CompanionConversationCodec()
    private let initializationLock = NSLock()
    private var initialized = false

    func initialize() async throws {
        try await run {
            self.initializationLock.lock()
            defer { self.initializationLock.unlock() }
            if self.initialized { return }
            registerModelServeCallback(provider: self.modelEndpoint)
            let support = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("OpenAICompanion", isDirectory: true)
            try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            _ = try self.value(appOpenStore(databasePath: support.appendingPathComponent("companion.sqlite").path))
            self.initialized = true
        }
    }

    func sessions() async throws -> [ChatSession] {
        try await run {
            try self.codec.sessions(valueJson: self.value(appListSessions())).map {
                ChatSession(id: $0.id, preview: $0.preview)
            }
        }
    }

    func createSession() async throws -> Int64 {
        try await run { try self.codec.sessionId(valueJson: self.value(appStartSession())) }
    }

    func openSession(_ id: Int64) async throws -> [ChatMessage] {
        try await run {
            _ = try self.value(appResumeSession(sessionId: id))
            return try self.decodedMessages(id)
        }
    }

    func loadSession(_ id: Int64) async throws -> [ChatMessage] {
        try await run { try self.decodedMessages(id) }
    }

    func deleteSession(_ id: Int64) async throws {
        try await run { _ = try self.value(appDeleteSession(sessionId: id)) }
    }

    func send(_ text: String, sink: LiveEventSink) async throws -> String {
        try await run {
            registerAgentEventSink(sink: sink)
            defer { unregisterAgentEventSink() }
            do {
                return try self.codec.output(valueJson: self.value(appSendMessage(userInput: text)))
            } catch {
                throw BridgeError.failure(self.modelEndpoint.latestError() ?? error.localizedDescription)
            }
        }
    }

    func cancel() {
        cancelAgentLoop()
        modelEndpoint.cancel()
    }

    private func decodedMessages(_ id: Int64) throws -> [ChatMessage] {
        try codec.messages(valueJson: value(appLoadSession(sessionId: id))).map {
            ChatMessage(role: $0.role, content: $0.content)
        }
    }

    private func value(_ result: AppResult) throws -> String {
        guard result.ok else { throw BridgeError.failure(result.error) }
        return result.valueJson
    }

    private func run<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do { continuation.resume(returning: try operation()) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }
}

enum BridgeError: LocalizedError {
    case failure(String)

    var errorDescription: String? {
        switch self {
        case .failure(let detail): detail
        }
    }
}
