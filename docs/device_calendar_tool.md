# 端侧 `get_events` 工具

## 组成

- `CalendarEventDataSource`：跨平台日历查询抽象。
- `AndroidCalendarEventDataSource`：通过 `CalendarContract.Instances` 查询 Android 日历。
- `IosCalendarEventDataSource`：通过 `EventKit` 查询 iOS 日历。
- `MacCalendarEventDataSource`：通过 `EventKit` 查询 macOS 日历。
- `DeviceMcpServer`：注册所有端侧工具并承载一个共享 MCP Server。
- `InProcessMcpServerConnection`：使用 Kotlin MCP SDK 的 `ChannelTransport`，在同一进程中连接 MCP Client 和 Server。

## App 初始化

App 启动异步 runtime 后，按以下顺序接入：

```kotlin
val manager = McpServerManager()
val deviceToolsHandle = startDeviceToolsServer(
    manager = manager,
    serverId = "device-tools",
) {
    registerCalendarTool(AndroidCalendarEventDataSource(applicationContext))
    // 后续继续在这里注册联系人、定位、提醒事项等端侧工具。
}
launch {
    registerMcpProvider(generatedHarnessBindings, manager)
}
```

iOS 使用 `IosCalendarEventDataSource()`，macOS 使用 `MacCalendarEventDataSource()`。
工具 Server 停止时调用 `deviceToolsHandle.close()`，它会一次性移除共享 Server 下的所有工具，再关闭进程内 MCP 连接。

端侧工具统一通过 `startDeviceToolsServer()` 注册和管理，不为单个工具单独提供 Server 启动入口。
Server 启动后也可以通过 `registerTool()` / `removeTool()` 动态变更工具；MCP Client 收到 `tools/list_changed` 后，KMP 会刷新聚合缓存并通过 UniFFI 推送给 Rust。

## 工具参数

`get_events` 接受 ISO 8601 日期范围：

```json
{
  "start_date": "2026-01-01T00:00:00+08:00",
  "end_date": "2026-01-02T00:00:00+08:00",
  "query": "项目",
  "limit": 20,
  "sort_order": "asc"
}
```

`start_date` 和 `end_date` 未提供时分别默认为今天 0 点和明天 0 点；`query` 按标题大小写不敏感匹配；`sort_order` 支持 `asc` 和 `desc`，`limit` 默认 20，范围为 1～500。返回结果会过滤已取消日程。工具执行发生在 KMP coroutine 中；权限拒绝、参数错误和系统查询异常会转换成 MCP `isError` 结果，再由 `McpToolProvider` 映射为统一的 `ToolExecutionError`。

## 平台权限

- Android：在 App manifest 声明 `android.permission.READ_CALENDAR`，并在调用前请求运行时权限。
- iOS：在 App 的 `Info.plist` 配置 `NSCalendarsUsageDescription`。
- macOS：配置日历使用说明和对应 App Sandbox/Entitlement；具体权限由宿主 App 管理。

## Rust Harness 调用链

Session 创建完成后应先调用 `ToolExecutor::initialize()` 完成一次工具快照加载；Agent Loop 不负责首次加载，后续仅处理 KMP 推送的快照版本变化。

```text
Agent Loop
  -> ToolRegistry.initialize()              # Session 初始化时首次拉取
  -> KMP tools/list_changed 推送
  -> ToolRegistry.sync_if_changed()        # 后续仅检查本地版本
  -> UniFFI ToolProvider.getTools()
  -> McpServerManager.tools()
  -> ChannelTransport -> DeviceMcpServer
  -> ToolRegistry.execute("get_events")
  -> ToolProvider.callTool()
  -> ChannelTransport -> EventKit / CalendarContract
```
