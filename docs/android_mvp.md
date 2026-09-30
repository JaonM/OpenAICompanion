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
- 当前主动任务和每 30 分钟主动发现由 App 进程内协程运行。进程退出后无法保证继续发现或按时推送；系统级后台调度仍待实现和设备验证。
- 现有日历数据源会检查 `READ_CALENDAR`，但移动 App 尚未提供完整的日历授权入口与端到端验收。

已使用 Android SDK 35、NDK 27.2.12479018、CMake 3.22.1 成功执行 `:androidApp:assembleDebug`，APK 包含 arm64-v8a 与 x86_64 的 Rust Harness、llama.cpp 和 JNA 原生库。Android 35 ARM64 模拟器上已完成安装、首屏启动及 Rust Harness 创建 `companion.sqlite` 的烟测，未发现启动崩溃。设备模型生成、MCP/OAuth、通知和重启恢复仍需端到端验收。
