# 本地持续对话轨迹

App 打开 SQLite 后调用 `MemoryStore::ensure_single_trace_session`：首次使用创建一条轨迹；旧版本留下的多条轨迹按 Turn ID 合并到同一条。App 的 `app_start_session` 现在只恢复这条轨迹，不再创建新的会话。`app_delete_session` 拒绝删除唯一会话；如需清除全部个人数据，应单独设计有明确确认步骤的清除入口。

每次 `Session::run_turn` 会先写入用户输入，再逐条写入助手消息、工具调用和工具结果；结束时记录状态、最终输出、推理文本、步数或错误。写入中的 Turn 状态为 `running`。失败、取消或异常中断的记录仍可查看，但不会进入后续推理的短期窗口或中期摘要。

推理时从 SQLite 读取最多 8 条已完成 Turn 的原始消息。更早的成功 Turn 每 4 轮由后台端侧模型生成一条摘要；摘要以 `(session_id, last_turn_id)` 存储，重试幂等。若摘要落后，下一轮会等待补齐检查点，避免短期窗口与摘要之间出现空缺。两段式中期记忆的读取规则见[单一会话的三层记忆](memory.md)。

轨迹包含用户原文、工具参数与返回值、助手输出，以及成功运行的推理文本。SQLite 文件目前未由 Harness 加密，应放在 App 私有目录；正式存储敏感个人资料前需接入加密存储。

## Markdown 消息展示

Mac、iOS 和 Android 共用 `MessageMarkdown`，用户消息、助手正文及手机远端结果支持标题、加粗/斜体、列表、引用、行内代码、代码块、链接和 GFM 表格。正文可选择复制；工具与折叠的思考过程继续展示原文。记录和推理消息仍保存原始文本，渲染只改变展示。

流式正文异步解析；追加内容时保留上一帧已格式化内容，解析完成后更新。初次加载或解析失败回退为原文。图片未接入网络加载，也未接入 LaTeX、Mermaid 或代码语法高亮。渲染依赖固定为与当前 Compose 兼容的 `multiplatform-markdown-renderer-m3:0.35.0`，升级需同时验证 JVM 渲染及 iOS 完整链接，参见[上游迁移说明](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/develop/MIGRATION.md)。

2026-10-10：窄屏 Compose 渲染测试验证未闭合代码块、流式补全后的表格与链接；实际预览已检查。JVM 回归 130 项通过、3 项跳过，Mac DMG 与 iOS 签名构建通过。Android 原生构建及三端实际对话的 Markdown 真机展示仍待验收。详见 `scripts/acceptance/results-2026-10-10.json`。
