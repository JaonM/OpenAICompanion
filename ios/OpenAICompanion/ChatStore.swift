import Foundation
import OpenAICompanionShared

@MainActor
final class ChatStore: ObservableObject {
    @Published private(set) var sessions: [ChatSession] = []
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var activeSessionID: Int64?
    @Published private(set) var modelStatus = "检查模型连接…"
    @Published private(set) var streamedReasoning = ""
    @Published private(set) var streamedText = ""
    @Published private(set) var isWorking = false
    @Published private(set) var isSending = false
    @Published var draft = ""
    @Published var errorMessage: String?
    @Published var modelSettings: ModelSettings

    private let bridge = HarnessBridge()
    private let streamAccumulator = CompanionStreamAccumulator()
    private var started = false

    init() {
        modelSettings = bridge.modelEndpoint.currentSettings()
    }

    func start() async {
        guard !started else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            try await bridge.initialize()
            sessions = try await bridge.sessions()
            started = true
            errorMessage = nil
            await refreshModelStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createSession() async -> Int64? {
        guard !isWorking else { return nil }
        isWorking = true
        defer { isWorking = false }
        do {
            let id = try await bridge.createSession()
            sessions = try await bridge.sessions()
            messages = []
            activeSessionID = id
            errorMessage = nil
            return id
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func openSession(_ id: Int64) async {
        guard !isWorking else { return }
        isWorking = true
        activeSessionID = nil
        messages = []
        streamedReasoning = ""
        streamedText = ""
        defer { isWorking = false }
        do {
            messages = try await bridge.openSession(id)
            activeSessionID = id
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteSession(_ id: Int64) async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            try await bridge.deleteSession(id)
            sessions = try await bridge.sessions()
            if activeSessionID == id {
                activeSessionID = nil
                messages = []
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = activeSessionID, !text.isEmpty, !isWorking else { return }
        isWorking = true
        isSending = true
        draft = ""
        errorMessage = nil
        streamedReasoning = ""
        streamedText = ""
        streamAccumulator.reset()
        messages.append(ChatMessage(role: "user", content: text))
        let (stream, continuation) = AsyncStream<LiveEvent>.makeStream()
        let receiver = Task { @MainActor in
            for await event in stream { apply(event) }
        }
        let sink = LiveEventSink { event in continuation.yield(event) }
        defer {
            continuation.finish()
            streamedReasoning = ""
            streamedText = ""
            isSending = false
            isWorking = false
        }
        do {
            _ = try await bridge.send(text, sink: sink)
            continuation.finish()
            await receiver.value
            messages = try await bridge.loadSession(id)
            sessions = try await bridge.sessions()
        } catch {
            continuation.finish()
            await receiver.value
            if error.localizedDescription != "生成已取消" {
                errorMessage = error.localizedDescription
            }
            if draft.isEmpty { draft = text }
            if let stored = try? await bridge.loadSession(id) { messages = stored }
        }
    }

    func cancel() {
        guard isSending else { return }
        bridge.cancel()
    }

    func saveSettings(_ value: ModelSettings) async {
        bridge.modelEndpoint.save(value)
        modelSettings = bridge.modelEndpoint.currentSettings()
        await refreshModelStatus()
    }

    func refreshModelStatus() async {
        modelStatus = await bridge.modelEndpoint.status()
    }

    private func apply(_ event: LiveEvent) {
        switch event {
        case .reasoning(let delta): streamedReasoning = streamAccumulator.addReasoning(delta: delta)
        case .text(let delta): streamedText = streamAccumulator.addText(delta: delta)
        case .completed, .error: break
        }
    }
}
