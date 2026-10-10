# 服务部署与运维

当前服务按单实例 SQLite 部署，不能直接增加应用副本。远程 APNs 和正式发行暂不实施；本指南不表示生产部署已完成。

## HTTPS 与代理信任

使用[部署配置](../deploy/service/compose.yaml)和[跨设备指南](cross_device_execution.md)准备域名、TLS、数据卷及凭据，应用端口不向公网发布。

Caddy 覆盖 `X-Companion-Client-IP` 为连接来源地址。后端只信任 `COMPANION_TRUSTED_PROXIES` 指定 IP 的该头；客户端伪造无效，可信代理漏传/传错地址时拒绝配对。Uvicorn 不信任通用代理头。

默认子网 `172.30.73.0/24`、代理 IP `172.30.73.2`。网络冲突时同时设置 `COMPANION_SERVICE_SUBNET`、子网内的 `COMPANION_PROXY_IP`；Compose 自动同步代理信任地址。自定义部署需明确填写实际代理连接来源 IP 并覆盖头，不可将任意客户端设为可信代理。叠加 CDN 时需单独设计信任链，不能直接信任客户端传入的 `X-Forwarded-For`。

## 健康、磁盘和错误检查

只读探针可接入现有监控系统或定时任务：

```sh
# COMPANION_METRICS_TOKEN 由已有秘密管理器注入，不放在参数中。
python3 scripts/service_check.py --url https://tasks.example.com \
  --data /secure/service-data --state /secure/probe/counters.json
```

`--data` 是探针执行主机上实际挂载的数据目录，默认空间阈值 1 GB（`--min-free-bytes` 可调）。检查 `/readyz`、磁盘及受鉴权 `/metrics`；只输出检查状态，不输出令牌或用户内容。退出码 0 为通过，1 为失败。

`/readyz` 以只读方式检查数据库版本和必要表；数据库丢失不会被健康检查自动创建。实际 Uvicorn HTTP 配对隔离、探针正常及缺表故障回归已通过；不替代正式代理/TLS 与告警演练。

提供 `--state` 时比较上次计数，默认至少 20 个新增请求且 5xx 比例超过 5% 才失败（`--min-requests`、`--max-error-rate` 可调）。首次执行/计数重置建立基线。低流量故障不一定达到请求阈值，可用性仍由 readiness 独立检查。

探针不发送消息，也不自动接入告警平台。部署者需配置定时执行、连续失败阈值、异常/恢复通知，并实际制造停服、磁盘阈值故障验证告警。队列积压、延迟分位数和通知质量需要业务监控。日志不可记录正文、令牌或 SQL 参数。

## 备份与恢复

执行 `scripts/service_admin.py --data <数据目录> backup --output <新备份目录>`；输出目录必须尚不存在。工具使用 SQLite backup API 纳入已提交 WAL 并检查完整性，不能复制正在运行的数据库文件。

建议至少每日备份，上线前确定实际 RPO/RTO、频率及保留期限。数据库尚无应用级加密，须采用加密磁盘和加密、访问受控的备份。旧备份包含旧凭据状态，恢复后必须重新核对撤销清单。

演练：停止隔离实例，恢复三库到新目录，核对属主/权限，启动并检查 readiness；验证有效授权、撤销拒绝、用户隔离、回执恢复与任务不重复执行。已有隔离三库恢复回归，正式主机演练待执行。不要覆盖运行中的服务或个人设备数据库。

## 容量与保留

上线前明确用户/设备数、队列上限、历史任务/日志期限、模型占用、备份预算，并做目标规模负载测试。目前不自动回收同步墓碑或执行回执；任意 TTL 可能导致离线设备恢复已删记忆或重复提交任务。安全回收需要设备确认水位及客户端重放窗口。

用户导出/删除使用管理员 CLI，按工具要求停服并确认用户。App 三档清理针对当前设备相应层级，不等于服务账户删除，见[记忆说明](memory.md)。

## CI 构建环境

CI 显式选择 Xcode 26.3，并检查 Metal 工具链，执行 Swift 检查、完整 iOS 模拟器 App 构建和 Mac 打包。[GitHub 官方 macOS runner 清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md)列出该版本及安装路径；远端结果以工作流实际运行记录为准。
