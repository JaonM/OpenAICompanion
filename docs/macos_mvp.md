# macOS MVP（KMP + Rust）

macOS 桌面端使用 Compose Desktop 作为 KMP/JVM 界面。Kotlin 侧处理模型 HTTP 请求、远程 MCP 连接与会话界面；Rust Harness 负责 Agent Loop、记忆和本地 SQLite 会话轨迹。两者通过 UniFFI 生成的 Kotlin/JNA 绑定通信。

## 运行

需要 macOS、JDK 21、Rust 工具链和网络（首次下载 Gradle/KMP/Compose 依赖）。在仓库根目录运行：

```bash
./scripts/macos-app.sh run
```

脚本会构建 `libharness.dylib`、更新 UniFFI Kotlin 绑定，再启动桌面应用。打包为 DMG：

```bash
./scripts/macos-app.sh package
```

DMG 由 Compose 的 `packageDmg` 任务生成，文件为 `kmp/build/compose/binaries/main/dmg/OpenAICompanion-1.0.0.dmg`，内含 `OpenAICompanion.app`。当前脚本只构建运行机器对应的 macOS 架构。
如果应用仍从默认构建目录运行，可设置 `COMPANION_BUILD_DIR=/private/tmp/openai-companion-qa-build` 再执行打包命令，将新版 DMG 写入独立目录而不覆盖运行中的应用。
验收时还可设置 `COMPANION_DATA_DIR` 指向独立的数据目录，避免测试会话写入默认的 `~/Library/Application Support/OpenAICompanion`。

首次打开应用后，进入“设置与模型”，填写 OpenAI 兼容的 Chat Completions URL 与模型名称。默认地址为本地 `http://localhost:11434/v1/chat/completions`，默认模型为 `hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M`；API Key 可留空或临时输入，**只保存在当前进程内存中**。接口地址和模型名称会记在本机偏好设置中。模型请求使用 SSE 流式 HTTP，推理内容和正文会增量显示。

本机运行推荐使用 Ollama：

```bash
brew install ollama
brew services start ollama
ollama pull hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M
```

该量化文件约 16.5 GB。当前开发机是 M5 Max、48 GB 统一内存；实测 Ollama 以 100% GPU 加载该模型，并使用 32,768 token 上下文。模型下载与服务安装不是 DMG 的一部分。首次拉取如果因网络中断失败，可以重试 `ollama pull`。如果已经通过其他方式取得完整的 `Qwen3.8-27B-UD-Q4_K_M.gguf`，也可用 `ollama create` 从该文件注册同名模型；不要把未校验的部分文件当成可用模型。当前开发机因额外 blob 下载连接重置，最终通过已完整缓存的 GGUF 本地注册成功。

本机集成验证（需要已经运行 Ollama 并下载模型）：

```bash
cd kmp
LOCAL_MODEL_INTEGRATION=1 HARNESS_LIBRARY_PATH=../harness/target/release/libharness.dylib \
  ./gradlew -PkmpJvmToolchain=21 jvmTest
```

可选填远程 MCP 服务地址。应用使用现有 KMP `McpServerManager` 和 Kotlin MCP SDK 连接服务，再把工具定义与调用转发给 Rust Harness。地址在连接成功后保存，下次启动会尝试恢复；每次发送消息前刷新工具列表，连接失败会清除旧工具快照并允许后续重连。远程地址必须为 HTTPS；本机 `localhost` 可用 HTTP。工具调用逐次展示名称与参数，需用户允许；25 秒未回应自动拒绝。当前仍只配置单个远程服务，尚未实现 OAuth 授权和真实模型到远程 MCP 的端到端验收。JVM 桌面 MVP 未接入 `macosMain` 中基于 EventKit 的端侧日历实现；日历能力可以先通过远程 MCP 服务使用。

## 会话和数据

应用自动恢复唯一的持续对话，界面不提供新建、切换或删除会话。旧版留下的多条轨迹会按 Turn ID 顺序合并。Rust 在 `~/Library/Application Support/OpenAICompanion/companion.sqlite` 存储中长期记忆与原始会话轨迹；原始对话、中期摘要和自动提取的中长期记忆点保存在同一数据库。SQLite 文件目前没有加密，正式存放敏感个人资料前需补加密存储。模型请求会发送到设置的接口地址。

启动时自动恢复唯一对话；推理前从 SQLite 读取最近 8 轮原始轨迹、中期摘要与长期画像。运行中的请求可取消，包括仍在等待模型响应的阶段；主动停止只显示“生成已取消”，不报故障。恢复历史时会标明失败、取消或可能中断的生成。模型和 MCP 连接错误会在窗口中显示。界面参考 LM Studio Bionic 的工作区层级，采用简化的设置侧栏、居中空状态和卡片式输入区；蓝色主操作与浅色/深色主题由 OpenAICompanion 自己定义，并未复制 Bionic 的品牌资源。支持 `⌘↵` 发送和 `⌘,` 打开设置。功能流程曾在当前机器使用隔离数据目录验证；本轮视觉调整已通过 JVM 编译，但仍需在新构建的窗口中进行人工视觉验收。
