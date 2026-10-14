# iOS 端侧工具扩展（P0 / P1）

本批在既有设备上下文与日历查询/新建之外，增加 9 个可选分组、28 个工具。健康、家庭优先建设。所有新分组默认关闭，在“设置 → 工具扩展”中独立开启；总开关仍控制整条本机工具连接。开启分组不立即请求系统权限，首次调用时按需请求。

## 已实现范围

| 分组 | 工具（均以 `device_` 开头） | 范围 |
|---|---|---|
| 健康 | `health_get_availability`、`health_query_statistics`、`health_list_sleep`、`health_list_workouts` | 只读；步数、步行/跑步距离、活动能量的每日累计；睡眠、运动原始记录 |
| 家庭 | `home_list_homes`、`home_list_rooms`、`home_list_accessories`、`home_get_state`、`home_set_characteristic`、`home_list_scenes`、`home_execute_scene` | 灯光开关/亮度、温控目标温度/模式及完全由支持动作组成的已有场景 |
| 提醒事项 | `reminder_list_lists`、`reminder_list_items`、`reminder_create_item`、`reminder_complete_item` | 列表、读取、创建和完成单个非重复任务 |
| 日历修改 | `calendar_list_editable_events`、`calendar_update_event` | 重新查询可编辑对象后修改单个非重复、非全天日程的起止时间 |
| 闹钟 | `alarm_list`、`alarm_create`、`alarm_cancel` | iOS 26+ 的 AlarmKit，本 App 所有的固定时间闹钟；旧系统不注册这些工具 |
| 联系人 | `contact_pick` | 用户通过系统界面选择一个联系人，返回姓名、最多各 10 个电话/邮箱 |
| 文件 | `file_pick`、`file_read_text` | 用户选择 UTF-8 TXT/Markdown、PDF 或音频；按字符偏移读取文本，按页读取文本 PDF |
| 图片采集 | `photo_pick`、`document_scan`、`image_recognize_text` | 系统照片选择器、最多 10 页扫描、Vision 本地 OCR |
| 音频 | `audio_record`、`audio_transcribe` | 前台录音 1～60 秒；选定/录制的最长 60 秒音频仅使用本地 Speech 识别 |

日历创建和查询继续使用既有共享实现。本轮健康授权与步数查询通过真机基础验收，家庭真实配件仍未验收；也不包含 PDF 编辑、音乐、导出分享等 P2 功能。暂不提供提醒事项修改、批量写入、重复事件编辑或任意第三方 App 控制。

## 调用与实现

```text
Rust Harness → McpToolProvider（既有审批）→ DeviceToolRegistry
    → NativeDeviceTool（共享策略、持久化操作日志）
    → IosNativeDeviceTools（异步 C 回调）
    → DeviceToolsBridge → 各 Swift 原生数据源
```

- `DeviceToolCatalog.swift` 是原生工具名、参数 Schema、分组与读写类型的单一来源。KMP 仅注册用户启用的分组；描述和参数 Schema 随工具快照更新。
- 所有新增工具均为个人数据/前台/逐次审批/不自动重试。策略由宿主生成，不从模型参数读取。
- 健康、HomeKit、提醒事项、相机、麦克风、Speech、AlarmKit 用途说明已添加；App entitlement 增加 HealthKit、HomeKit。签名部署需对应 provisioning profile 支持这些能力。
- 可信的前台端侧工具在 Harness 获得至少 180 秒交互预算，远程工具和公共只读工具保持原预算；原生调用串行执行，另有调用上限 90 秒。只读交互超时返回 `INTERACTION_TIMEOUT`，写入超时保留 `OUTCOME_UNKNOWN`；用户取消、超时与迟到回调只交付一次结果，选择器迟到回调绑定原始交互，不得完成后续选择器。
- 原生返回使用既有 `DeviceToolResult`；单次数据最多 32 KiB，超限要求缩小查询，不能静默丢弃记录。
- 本机处理不等于结果永不离机：返回数据沿用当前会话、模型和 trace 路径；若当前模型为远程服务，工具结果会供该服务处理。设置和既有审批界面有说明。

## 写入行为

全部新写工具要求 `request_id`（8～128 个 ASCII 字母、数字、连字符或下划线）。共享 `NativeDeviceTool` 规范化 JSON 后，通过既有 SQLite `DeviceOperationJournal` 原子占用请求；同 ID/同参数重放成功结果，不同参数拒绝。开始写入后，外层协程取消不释放日志占用。Swift 在权限完成、用户确认后检查前台状态。

本批采用保守恢复：**结果不确定的写入不会自动执行第二次**。与既有日历创建的系统标记恢复不同，新写工具没有实现每种系统数据源的自动恢复；重启、回调丢失或日志落盘失败后，需查看系统状态人工核实。失败或取消发生在占用之后，也可能保留未确认记录；不要自动生成新 ID 重写。没有跨 SQLite 与系统数据源的原子事务保证。

提醒事项完成、日程修改以短期引用保存批准前对象版本；确认后重新读取并校验，变化则拒绝。设备控制还有一层原生可读确认，显示设备、房间与目标值；场景显示动作预览，并在确认后重新检查动作集合及权限。

## 各类边界

### 健康

- 时间区间最多 31 天；统计需显式 IANA 时区，返回单位及每日边界。首尾日可能是不完整日。
- 统计使用 HealthKit cumulative statistics；没有可读样本的值为 `null`，不可转换为零。空记录也不证明用户拒绝了读取权限。
- 睡眠和运动保留来源；睡眠重叠记录不求和，避免将多设备或“卧床/睡眠阶段”叠加为总睡眠时长。
- iOS 27 授权页可限制共享的历史范围；查询结果只代表系统当前允许读取的数据，不能代表完整健康历史。
- 样本超过 500 条或结果超出大小限制时要求缩小时间范围。
- 本批无健康写入、诊断或自动健康触发家庭控制。

### 家庭

- 引用保留 5 分钟，过期需重新发现；设备移除、授权变化、不可达或属性不支持会失败。
- 只允许灯光和温控的有限属性，遵守设备 min/max/step/validValues。温度缺少边界元数据时拒绝控制。
- 门锁、车库门、安防以及含任何不支持动作的场景不执行；场景最多 30 个动作。
- 写入确认只表示系统接受命令，不表示实际物理状态已验证；随后使用状态读取核对。场景失败可能已部分执行，不自动重发。
- 没有可访问家庭时返回空列表及明确说明；没有真实配件环境不阻塞其他工具，但必须保留待验收状态。

### 材料与交互

- 资源引用仅在当前 App 进程有效，10 分钟过期，不暴露原始文件路径、不跨设备共享。资源副本放临时目录，启动清理旧副本，运行中到期清理；最多 20 个资源，每个 25 MiB。
- 文件读取默认首个 PDF 页或文本开头，最多 12,000 字符；后续使用 `page`/`offset`，返回页数和下一偏移。扫描 PDF 不会伪装成已提取正文，需使用图片扫描/OCR 路径。
- OCR 最多 100 个文字块、12,000 字符，提供置信度和归一化坐标及截断标记。
- 录音在后台或取消时停止。Speech 在设备/语言不支持本地识别时明确失败，不自动上传音频。
- 闹钟只管理本 App 的 AlarmKit 闹钟，不读取“时钟”App 全部闹钟。

## 验证

共享回归（先构建当前 Rust 动态库，避免旧库符号不匹配）：

```bash
cd harness
cargo build --lib
cd ../kmp
HARNESS_LIBRARY_PATH="$PWD/../harness/target/debug/libharness.dylib" ./gradlew \
  -PkmpJvmToolchain=21 -PskipAndroidApp=true :iosApp:compileKotlinIosSimulatorArm64 \
  jvmTest --tests '*NativeDeviceToolTest' --tests '*DeviceToolContractTest' \
  --tests '*DeviceOperation*Test' --tests '*ToolPolicyTest' \
  --tests '*CalendarCreateToolTest' --tests '*MobileMcpServiceTest'
```

原生策略/资源验证（使用已启动的模拟器，不访问真实健康和家庭数据）：

```bash
./scripts/test-ios-device-tools.sh <模拟器-UDID>
```

2026-10-13：Kotlin iOS 编译通过；上述共享回归 **35 通过、0 失败、0 跳过**。原生模拟器测试覆盖 Schema 类型与未知字段拒绝、日期边界、家庭允许列表、值约束、回调只完成一次、原生调用串行与迟到释放隔离、资源不透明引用，以及合成材料的 UTF-8 分段读取、PDF 提取、真实 Vision OCR；已通过。Rust 库全部 83 项测试通过，覆盖可信策略决定交互预算、远程工具不能通过名称获取更长预算。此结果不是 HomeKit 配件模拟器端到端验收。完整 iOS 27 模拟器 App 已通过无签名构建和链接。设置页 UI 测试 **1 通过、0 失败、0 跳过**，验证全部九个分组可见、打开设置不请求权限；已导出截图核对布局。最终证据为 `/tmp/companion-device-tools-ui-final.xcresult`，汇总见 [验收记录](../scripts/acceptance/ios-device-tools-2026-10-13.json)。

真机累计 **12 个不同用例通过**：九组设置可见性；健康可用性与步数授权查询；家庭授权发现；提醒事项列表授权；日历可编辑事件查询；闹钟创建/列表/取消；联系人、文件、照片和扫描取消；录音确认取消。中间混合运行保留失败记录，不能按整组通过计算。

未验收范围：健康撤销与系统健康 App 对账、多来源睡眠/运动；HomeKit 配件模拟器与真实控制/状态同步/部分失败；提醒事项创建/完成和日历修改的真实账户写入；AlarmKit 锁屏提醒；成功选取联系人/照片/文件、实际扫描采集，实际录音/中断和本地 Speech 识别；VoiceOver、大字体及其他系统/机型/模型组合。


### 真机验收补充（2026-10-13）

本轮使用 iPhone 17 Pro Max / iOS 27.0.1 与本地 MLX 模型，通过 XCTest 输入请求、核对逐次审批、操作系统界面，并解析实际工具消息中的 `DeviceToolResult`。工具测试通过启动参数单独开启分组，不覆盖用户保存的分组开关。系统授权按实际弹窗操作。

验收发现并修复：新写工具被旧 SQLite 日志白名单拒绝；`load_more_tools` 的非 JSON schema 阻断模型请求编码；原生确认弹窗替换 UIKit 私有 presentation delegate 导致崩溃；只读交互超时误报可能发生写入。补充了真实 Rust/SQLite 重启重放测试、完整模型请求编码回归，以及读写超时分类测试。

真机用例位于 `ios/UITests/OpenAICompanionUITests.m`，以 `testNative` 开头。依赖原生权限或本地模型的用例在模拟器上跳过；设置页用例仍可在模拟器运行。闹钟用例创建次日、带“Companion 验收（自动清理）”标题的 App 闹钟，核对列表后在 `@finally` 取消。文件取消用例适配 iOS 27 的下拉关闭交互，健康查询用例只勾选步数读取权限，并选择 iOS 27 的“过去30天及后续数据”范围。最终通过项、失败尝试与待验收边界记录在 [验收记录](../scripts/acceptance/ios-device-tools-2026-10-13.json)。
