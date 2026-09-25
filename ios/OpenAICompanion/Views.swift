import SwiftUI
import OpenAICompanionShared

struct SessionListView: View {
    @StateObject private var store = ChatStore()
    @State private var path: [Int64] = []
    @State private var search = ""
    @State private var showingSettings = false
    @State private var deleting: ChatSession?

    private var filteredSessions: [ChatSession] {
        guard !search.isEmpty else { return store.sessions }
        return store.sessions.filter { $0.title.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if filteredSessions.isEmpty {
                    ContentUnavailableView(
                        search.isEmpty ? "还没有会话" : "没有找到会话",
                        systemImage: "bubble.left.and.bubble.right",
                        description: Text(search.isEmpty ? "点击右上角的加号开始对话。" : "试试其他关键词。")
                    )
                } else {
                    List(filteredSessions) { session in
                        NavigationLink(value: session.id) {
                            Label {
                                Text(session.title).lineLimit(2)
                            } icon: {
                                Image(systemName: "bubble.left")
                                    .foregroundStyle(.indigo)
                            }
                            .padding(.vertical, 5)
                        }
                        .disabled(store.isWorking)
                        .swipeActions {
                            Button(role: .destructive) {
                                deleting = session
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            .disabled(store.isWorking)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .searchable(text: $search, prompt: "搜索会话")
            .navigationTitle("会话")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingSettings = true } label: {
                        Label("设置", systemImage: "gearshape")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task {
                            if let id = await store.createSession() { path.append(id) }
                        }
                    } label: {
                        Label("新建会话", systemImage: "square.and.pencil")
                    }
                    .disabled(store.isWorking)
                }
            }
            .navigationDestination(for: Int64.self) { id in
                ChatView(store: store, sessionID: id)
                    .task { await store.openSession(id) }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView(store: store)
            }
            .alert("删除会话？", isPresented: Binding(
                get: { deleting != nil },
                set: { if !$0 { deleting = nil } }
            )) {
                Button("取消", role: .cancel) { deleting = nil }
                Button("删除", role: .destructive) {
                    guard let session = deleting else { return }
                    deleting = nil
                    Task { await store.deleteSession(session.id) }
                }
            } message: {
                Text("这会永久删除本机保存的会话和轨迹。")
            }
            .task { await store.start() }
        }
    }
}

private struct ChatView: View {
    @ObservedObject var store: ChatStore
    let sessionID: Int64
    @State private var showingSettings = false

    private var title: String {
        store.sessions.first(where: { $0.id == sessionID })?.title ?? "新会话"
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if let error = store.errorMessage {
                        Label(error, systemImage: "exclamationmark.circle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(store.messages) { message in
                        MessageRow(message: message).id(message.id)
                    }
                    if store.isSending {
                        StreamingMessage(
                            reasoning: store.streamedReasoning,
                            text: store.streamedText
                        )
                        .id("streaming")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: store.messages.count) { _, _ in
                if let last = store.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
            .onChange(of: store.streamedText) { _, _ in
                if store.isSending { proxy.scrollTo("streaming", anchor: .bottom) }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ComposerView(store: store)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showingSettings = true } label: {
                    Label("模型设置", systemImage: "slider.horizontal.3")
                }
            }
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(store: store)
        }
    }
}

private struct MessageRow: View {
    let message: ChatMessage
    @State private var reasoningExpanded = false

    var body: some View {
        switch message.role {
        case "reasoning":
            DisclosureGroup("查看推理过程", isExpanded: $reasoningExpanded) {
                Text(message.content)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .padding(.top, 8)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        case "status":
            Text(message.content)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
        case "user":
            HStack {
                Spacer(minLength: 48)
                Text(message.content)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.indigo.opacity(0.12), in: RoundedRectangle(cornerRadius: 18))
                    .textSelection(.enabled)
            }
        default:
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sparkle")
                    .foregroundStyle(.indigo)
                    .frame(width: 25, height: 25)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(message.role == "tool" ? "工具" : "Companion")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(message.content)
                        .textSelection(.enabled)
                }
                Spacer(minLength: 16)
            }
        }
    }
}

private struct StreamingMessage: View {
    let reasoning: String
    let text: String
    @State private var expanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Companion · 生成中", systemImage: "sparkle")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            if !reasoning.isEmpty {
                DisclosureGroup("推理过程", isExpanded: $expanded) {
                    Text(reasoning)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            if !text.isEmpty { Text(text).textSelection(.enabled) }
            if text.isEmpty { ProgressView().controlSize(.small) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ComposerView: View {
    @ObservedObject var store: ChatStore

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("给 Companion 发送消息…", text: $store.draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color(uiColor: .secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 17))
                    .disabled(store.isSending)
                if store.isSending {
                    Button { store.cancel() } label: {
                        Label("停止生成", systemImage: "stop.fill")
                    }
                        .buttonStyle(.borderedProminent)
                        .labelStyle(.iconOnly)
                        .accessibilityLabel("停止生成")
                } else {
                    Button { Task { await store.send() } } label: {
                        Label("发送", systemImage: "arrow.up")
                    }
                        .buttonStyle(.borderedProminent)
                        .labelStyle(.iconOnly)
                        .disabled(store.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.isWorking)
                }
            }
            Text("\(store.modelStatus) · \(store.modelSettings.model)")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(.regularMaterial)
    }
}

private struct SettingsView: View {
    @ObservedObject var store: ChatStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: ModelSettings

    init(store: ChatStore) {
        self.store = store
        _draft = State(initialValue: store.modelSettings)
    }

    private var validationError: String? {
        CompanionModelDefaults().validationError(endpoint: draft.endpoint, model: draft.model)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("模型连接") {
                    TextField("Chat Completions 地址", text: $draft.endpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("模型名称", text: $draft.model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("API Key（仅本次运行）", text: $draft.apiKey)
                } footer: {
                    if let validationError {
                        Text(validationError).foregroundStyle(.red)
                    } else {
                        Text("iPhone 可连接同一局域网内的 Mac Ollama 服务。不要将未认证的模型服务开放到公网。")
                    }
                }
                Section("状态") {
                    LabeledContent("模型", value: store.modelStatus)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        Task {
                            await store.saveSettings(draft)
                            dismiss()
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(validationError != nil)
                }
            }
        }
    }
}
