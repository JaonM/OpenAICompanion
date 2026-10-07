# 统一端侧工具（v1）

iOS、macOS、Android 使用相同的工具名、JSON Schema、参数校验与结果结构。现包含设备上下文、日程查询、日历列表和新建日程。

## 调用链与代码组织

```text
Rust Harness -> UniFFI ToolProvider -> McpToolProvider（按策略确认）
    -> McpServerManager -> DeviceToolConnection -> DeviceToolRegistry
        -> DeviceContextTool
        -> CalendarListTool -> 平台 CalendarEventDataSource
        -> CalendarCatalogTool / CalendarCreateTool -> 平台 CalendarWriteDataSource
```

- `commonMain/.../device/`：注册表、策略默认配置、统一返回值、设备上下文及日历契约。
- `calendar/CalendarCreateTool` 只定义工具契约并委托执行；`CalendarCreateRequest` 校验、规范化参数并生成结果；`CalendarCreateService` 负责权限预检、请求占用、保存和恢复状态流程。
- `DeviceOperationJournal` 使用类型化状态区分不存在、冲突、待确认和成功；`lookup` 与原子 `claim` 分开，只有 `Acquired` 允许系统写入。`BindingsDeviceOperationJournal` 隔离并校验既有 UniFFI JSON 协议，参数规范化版本和 SQLite 表结构保持兼容。
- Rust `DeviceOperationStore` 独立封装设备操作日志 SQL，复用应用 SQLite 连接；`MemoryStore` 暂保留兼容入口，不承担日志 SQL 实现。
- `MobileMcpService` 启动时注册本机 connection，远程 MCP 未配置、断开或授权失败都不移除本机工具。
- iOS：`IosCalendarEventDataSource` 使用 EventKit；Android：`AndroidCalendarEventDataSource` 使用 `CalendarContract.Instances`，覆盖重复日程的实例。
- macOS 实际 App 是 JVM Compose。`DesktopCalendarEventDataSource` 通过 JNA 调用 `macos/CalendarBridge/CalendarBridge.m`，使用 EventKit。平台数据源统一由当前三端宿主注入。
- 端侧工具只使用共享 `DeviceToolRegistry` 和 `DeviceToolConnection`，无需 HTTP 端口或额外 MCP 服务。远程 MCP 客户端保持独立。

`createDeviceTools(platform, language, calendar, isForeground, journal)` 安装同一组工具。可写数据源必须注入 `BindingsDeviceOperationJournal`，缺失时禁止注册写工具，不能退回内存去重。注册/移除会刷新 Harness 工具快照；同一注册表中的名字移除后不可复用，避免用户批准期间替换同名实现。宿主替换整条 connection 时，需要重新批准调用。

`ToolPolicy` 是共享策略模型，`DeviceToolPolicies` 提供公共只读、个人只读和个人写入三组默认配置。策略描述数据类型、读写和前台要求。日历查询在执行前后检查前台状态；新建日程在确认、权限检查后和提交前检查前台状态，提交后离开前台不把已经保存的结果改报失败。系统权限在查询和缓存翻页时检查。策略通过 UniFFI 的 `ToolPolicy` 传给 Rust，并随工具快照更新。日历工具仍经逐次 UI 确认；公共设备上下文无需确认，可在后台调用。确认框的“本机”来源来自 connection 类型，远端工具名不能获得本机身份。

## 工具

### `device_get_context`

参数为 `{}`，返回 `platform`、`time`、`timezone`、`local_time`、`language`。不读取位置、设备标识、联系人；本批不提供电量与网络状态。

### `device_calendar_list_events`

首屏示例：

```json
{
  "start": "2026-10-07T00:00:00+08:00",
  "end": "2026-10-08T00:00:00+08:00",
  "query": "项目",
  "limit": 20,
  "fields": ["title", "start", "end", "all_day"]
}
```

- `start/end` 必须带时区，区间正向且最多 31 天；采用相交区间筛选，排除已取消日程。
- `query` 可省略，按标题进行不区分大小写的包含匹配，最多 200 字符。
- `limit` 默认为 20，范围 1～100。每页事件 JSON 总量另限制在约 16,000 字符，长备注可能导致每页少于 limit 条；剩余项通过游标继续读取。单次匹配超过 500 条时要求缩小范围，不静默漏掉余下日程。
- `fields` 默认四个基础字段；可额外请求 `calendar`、`location`、`notes`。地点和备注默认不读取。未知参数、重复字段和不合法的类型都会被拒绝。
- 按开始时间、结束时间、原生 ID 排序；返回临时 `event_ref`，不暴露原生日程标识。
- 标题/日历名/地点/备注分别限制为 500/200/1000/2000 字符，发生裁剪时在每条记录的 `truncated_fields` 中明确列出。

翻页只传 `{"cursor":"上一页返回的 next_cursor"}`，不能同时改变日期或字段。每个快照最多保留两分钟，每个工具实例最多三个快照；进程重启、超期或被新查询挤出后返回 `CURSOR_EXPIRED`，需重新查询。期间日历变化不影响现有分页结果，但权限撤销会阻止返回缓存。`event_ref` 也只在该快照中有效。

### `device_calendar_list_calendars`

参数为 `{}`，返回 `data.calendars`，每项包含 `id`、`title`、`writable`。新建前先调用此工具，由用户确认目标可写日历，避免误写入其他账户。没有可写日历时需先在系统日历配置账户。

### `device_calendar_create_event`

```json
{
  "request_id": "meeting-20261007-001",
  "calendar_id": "从日历列表取得的 id",
  "calendar_title": "工作",
  "title": "项目评审",
  "start": "2026-10-07T09:00:00+08:00",
  "end": "2026-10-07T10:00:00+08:00",
  "time_zone": "Asia/Shanghai",
  "location": "会议室 A",
  "notes": "讨论发布计划"
}
```

必填 `request_id/calendar_id/calendar_title/title/start/end/time_zone`，可选 `location/notes/all_day`。标题、地点、备注分别最多 500/1000/2000 字符；时长必须为正且不超过 367 天。工具创建单个日程，不支持参与者、重复规则、提醒或更新/删除操作；未支持的参数直接拒绝。

`all_day=true` 时，`start/end` 改为 `YYYY-MM-DD` 日期，结束日期不包含在日程内，例如 `2026-10-07` 到 `2026-10-08` 表示一天。使用指定 IANA 时区解释日期；Android 在存储时转换为 Calendar Provider 要求的 UTC 午夜，Apple 使用该时区的本地日期边界。

`calendar_title` 从列表复制，在确认框中展示可读的目标日历名；提交前会与真实名称核对，不一致时要求重新选择并确认。

成功后 `data` 返回 `created=true`、`event_id`、`calendar_id`、`request_id` 和日程摘要。新查询可读取刚创建的日程，已经发出的分页快照仍保持原样。

写入始终经过原有 UI 确认，执行期间会检查系统写权限和目标日历是否可写。工具策略为 `effect=write`，禁止后台自动写入。系统保存开始后会完成该次保存并记录结果；外层调用取消不触发第二次保存。

新建前，在现有 `companion.sqlite` 的 `device_operations` 表中原子占用 `(tool, request_id)`，记录规范化参数、随机 `operation_id`、状态和结果。状态为 `pending / succeeded / unknown`，成功记录不可被降级覆盖，没有自动超时释放或淘汰。相同 ID 和参数重放已保存结果，不同参数复用 ID 被拒绝。SQLite 事务保证多实例同时请求时只有一个能取得写入资格。

日程保存时，Apple 的 URL、Android 的自定义 URI 同时写入 `openai-companion://calendar/operations/<operation_id>`。恢复过程如下：

1. `succeeded`：直接读取持久化结果，无需再次保存日程。
2. `pending/unknown`：按原目标日历和标记查询系统日历；Apple 查询原时间段前后各扩展一天。
3. 唯一匹配：记录成功结果并返回；没有匹配、多个匹配或系统查询失败：保持未确认状态，不执行新写入。
4. 权限被撤销：要求重新授予读取权限后，再使用同一个 `request_id` 恢复。

数据库在系统保存前不可用时不执行写入；系统已保存但成功日志未落盘时，下次通过标记恢复。外层调用取消不会丢弃已开始的保存结果。去重跨进程重启和 Activity 重建有效，但不能与系统日历组成同一个数据库事务，因此不宣称无条件的 exactly-once。若用户移动日程、删除标记或账户不保留标记，恢复可能一直无法确认；此时需人工核实，而非自动用新请求重写。

操作日志只保存在本设备，不进入记忆同步。改造前创建的日程没有日志/标记，无法追溯去重；卸载、清库或迁移到另一设备也不属于该保证。取消原有 256 条内存记录上限，持久化记录不自动清理，以免旧请求重新产生副作用。

## Harness 执行策略

`ToolPolicy` 包含版本、来源、读写类型、数据类别、前台要求、后台可用性、确认要求和重试模式。策略仅由宿主生成；远端工具描述中的自报策略不会被采纳。

| 工具 | 数据/效果 | 前台 | 后台可用 | 逐次确认 | 自动重试 |
|---|---|---|---|---|---|
| 设备上下文 | public / read | 否 | 是 | 否 | safe_read，仅可重试错误 |
| 日程查询、日历列表 | personal / read | 是 | 否 | 是 | never |
| 新建日程 | personal / write | 是 | 否 | 是 | never |
| 无可信策略的远程工具 | unknown / unknown | 是 | 否 | 是 | never |

Rust 主动规划器只选择明确允许后台的公共只读工具，不再根据 `get_` 等前缀猜测。运行既有任务时，执行器也会按当前策略过滤和拒绝工具；旧规则的 `allowed_tools` 不能越过策略。此前只因名字前缀被视为可后台查询的远程工具，现在默认被排除；当前宿主尚未提供远程工具的后台授权配置 UI。

调用时通过 UniFFI 明确传递 `foreground/background` 上下文；KMP 在调用确认前再次验证策略，后台拒绝直接返回权限错误，不弹出确认框。所有 MCP 写工具与未知工具禁止自动重试；只有显式 `safe_read`、无需确认的只读工具允许重试暂时性错误。正常会话中需要确认的调用仍由宿主 UI 决定，KMP 继续检查设备真实前台状态。

策略作为独立字段更新，不拼进参数 Schema；模型不能通过工具参数更改它。策略变更也参与待批准调用的身份检查，避免用旧批准执行新策略的工具。

## 统一结果

```json
{
  "version": 1,
  "status": "ok",
  "data": {"events": []},
  "source": {"device": "current", "platform": "ios"},
  "observed_at": "2026-10-07T00:00:00Z",
  "next_cursor": null,
  "truncated": false
}
```

`status` 为 `ok`、`requires_user_action`、`unavailable` 或 `error`；非成功结果同时设置 `McpCallResult.isError=true` 并提供 `error.code/message/retryable`。权限不足不会伪装成空日程；查询取消直接传播到 Harness；已经开始的新建保存会先记录完成或不确定结果，供同一请求重试查询。`truncated` 表示仍有后续页，字段裁剪见记录中的 `truncated_fields`。`observed_at` 是结果生成时间，翻页内容来自前述短期快照。

日历结果将进入会话并供当前模型处理；使用远程模型时可能发送到配置的模型服务，按现有 trace/记忆机制存储。确认框和工具描述均提示模型会处理结果。

## 平台授权与构建

- Android Manifest 已声明 `READ_CALENDAR` 和 `WRITE_CALENDAR`，查询仅申请读权限，新建时申请读写权限。`AndroidCalendarPermission` 在前台触发系统授权，拒绝时返回可操作错误；已永久拒绝时需到系统设置开启。取消调用不会将旧权限回调错误交给后续请求。
- iOS 最低版本 17，使用 `requestFullAccessToEventsWithCompletion`，Info.plist 已声明 `NSCalendarsFullAccessUsageDescription`。
- macOS 使用 `scripts/macos-app.sh package` 构建并运行打包的 `.app`；脚本编译并携带 `libcompanion_calendar.dylib`，Compose 打包配置写入日历用途说明。`scripts/macos-app.sh run` 的普通 JVM 不一定有 App Info.plist，未授权时会返回“请运行打包 App”，避免无用途说明触发系统终止。当前桌面包未启用 App Sandbox；未来启用时需添加日历 entitlement。

权限弹窗受 Harness 调用超时约束。首次授权等待超时后，完成系统授权并重新调用即可；只读工具不会申请 Android 写权限；Apple 读写复用完整日历授权。

## 验证

`DeviceToolContractTest` 覆盖跨平台契约一致性、最小字段、稳定分页、权限撤销、游标过期、参数边界、前台限制、批准期间移除工具以及协程取消。`DesktopBridgeTest.deviceCalendarRoundTripsThroughRustWithoutRemoteMcp` 使用真实 UniFFI/Rust Harness 与测试数据源验证工具发现、确认、调用和结果回传。`CalendarCreateToolTest` 还覆盖创建去重、并发与取消、写权限、只读目标、无效参数、跨夏令时全天日程，以及写入后离开前台的结果一致性。`DeviceOperationPersistenceTest` 使用真实 Rust/SQLite 验证重开数据库、丢失保存确认后的标记恢复和未确认写入不重复执行；Rust 测试覆盖并发占用、持久化状态和后台/重试策略，`ToolPolicyTest` 覆盖 KMP 后台拒绝与远端伪造策略。`DeviceOperationProtocolTest` 另外验证异常协议状态不能取得写入资格，以及旧版参数编码兼容性。测试不读取或修改开发机私人日历；真实设备的授权弹窗和日历账户数据仍需人工验收。
