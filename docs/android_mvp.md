# Android MVP

Android App 位于 [`kmp/androidApp`](../kmp/androidApp)。界面、状态控制器、MCP 连接与 OAuth 流程、A2A 客户端和存储编解码、会话编码及 Rust UniFFI JVM 绑定与其他平台共享；Android 平台层负责 GGUF 文件导入、llama.cpp JNI、OAuth 浏览器回跳、Keystore 凭据、通知和系统数据源。Rust Harness 通过 `libharness.so` 在设备本地保存会话、记忆和主动任务。

## 构建

需要 JDK 21、Android SDK Platform 35、Build Tools 35、CMake 3.22.1、NDK 27.2.12479018、Rust 及 `aarch64-linux-android` 和 `x86_64-linux-android` Rust 目标。Android SDK 的许可须由开发者接受。设置 `ANDROID_HOME` 后，在 `kmp/` 执行：

```sh
./gradlew -PkmpJvmToolchain=21 :androidApp:assembleDebug
```

先运行 `./scripts/android-llama.sh` 获取固定版本 llama.cpp。Gradle 在合并 JNI 库前调用 [`scripts/android-rust.sh`](../scripts/android-rust.sh)，为 arm64 真机和 x86_64 模拟器编译并打包 Rust Harness；CMake 从仓库内 `ios/Vendor/llama.cpp` 编译同版本 llama.cpp。产物和 Android SDK 不提交到 Git。没有 Android SDK 的机器可用 `-PskipAndroidApp=true` 运行 KMP JVM/iOS 构建与测试。

## 运行边界

- App 通过 Android 系统文件选择器导入 GGUF 到应用私有目录，在设备上完成模型推理。MCP 连接仅接受 HTTPS 或本机 HTTP；OAuth 令牌和 A2A Bearer 令牌使用 Android Keystore 加密保存。
- OAuth 使用系统浏览器与 `openai-companion://oauth/callback` 回跳；主 Activity 复用既有实例接收回跳，再由共享 KMP OAuth 客户端校验授权响应。
- 通知在启用主动任务时申请 Android 13 及以上版本的 `POST_NOTIFICATIONS` 权限。用户拒绝时任务结果不会标记为已投递。
- 主动推送默认关闭，可在设置中开启并选择 15 分钟、30 分钟、1 小时或 3 小时的发现间隔；任务到点执行和通知也受总开关控制。固定时间提醒已接入持久化 AlarmManager 排程及重启恢复；缺少精确闹钟权限时可能延迟。主动发现与实时推理仍需 App 可运行，系统强行停止和权限变化需真机验收。
- 中长期记忆可通过自建 HTTPS 服务跨端同步，Android 使用 Keystore 保存访问令牌；服务部署与配置见[跨端记忆同步](memory_sync.md)。
- 现有日历数据源会检查 `READ_CALENDAR`，但移动 App 尚未提供完整的日历授权入口与端到端验收。

已使用 Android SDK 35、NDK 27.2.12479018、CMake 3.22.1 成功执行 `:androidApp:assembleDebug`，APK 包含 arm64-v8a 与 x86_64 的 Rust Harness、llama.cpp 和 JNA 原生库。Android 35 ARM64 模拟器上已完成安装、首屏启动及 Rust Harness 创建 `companion.sqlite` 的烟测，未发现启动崩溃。设备模型生成、MCP/OAuth、通知和重启恢复仍需端到端验收。

## 与 iOS 同步的移动界面

2026-10-08 核对：Android `MainActivity` 直接调用 `commonMain` 的 `CompanionMobileScreen`，以下 iOS 界面修改已同时适用于 Android，无需维护第二份界面。

| 功能 | Android 当前实现 |
| --- | --- |
| 对话输入 | 默认一行、最多四行的紧凑输入框，纸飞机发送按钮；生成期间提供停止按钮。共享界面使用安全区域及键盘边距。 |
| 执行记录 | 工具和思考过程以灰色小字单行省略，点击展开全文。 |
| 远端任务 | 顶栏“任务”进入独立任务页；对话顶部无常驻任务状态/刷新栏，任务页保留刷新、回复及取消。 |
| 设置 | 七个功能分组，支持返回“所有设置”及“完成”。 |
| 导航与阅读 | 页面切换保留草稿；阅读历史时保持位置，提供“最新消息”入口。 |

本次共享 JVM 编译通过（`/tmp/companion-android-ui-shared-1008.log`）。Android APK 构建尝试因当前环境缺少 SDK 而未执行（`/tmp/companion-android-ui-build-1008.log`）；上文的历史 APK/模拟器烟测不代表本次版本验收通过。当前版本的 Android 构建、键盘适配、上述交互及跨设备真机验收仍待完成。
