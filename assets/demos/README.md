# iOS Demo 素材

每个重点能力保留一个代表场景，共三个 GIF。均为真机验收截图的局部裁剪与分步轮播，叠加演示标题；没有重绘 App 界面或补造执行结果。每帧停留 4.5 秒，不代表推理或交互耗时。点击根 README 中的图片可查看原尺寸。

| 文件 | 场景与来源 | 演示边界 |
| --- | --- | --- |
| [ios-health.gif](ios-health.gif) | 2026-10-13，`testNativeHealthStatisticsPermissionAndQuery`；同一截图依次展示步数查询参数与返回结果 | 健康日期明细与步数已不透明遮挡；仅说明查询返回成功，不代表与健康 App 对账完成。 |
| [ios-alarm.gif](ios-alarm.gif) | 2026-10-13，`testNativeAppAlarmCreateListCancel`；创建、列表、取消三张结果截图 | 同一验收闹钟，取消成功；未展示锁屏响铃。 |
| [ios-cross-device.gif](ios-cross-device.gif) | 2026-10-10，`testMlxCrossDeviceDelegatesToMacAndShowsResult`；同一完成结果截图的调用记录与任务卡片 | 展示 iPhone 委托 Mac 计算 `17 + 25` 并收到 `42`；没有展示实时状态变化或后台推送。 |

家庭尚无真实配件控制验收，故不制作控制成功演示。联系人、文件、照片、扫描和录音的取消用例也不作为完整功能成功演示。

验收依据：[端侧工具记录](../../scripts/acceptance/ios-device-tools-2026-10-13.json)、[2026-10-10 记录](../../scripts/acceptance/results-2026-10-10.json)。原始截图含个人数据，不纳入仓库；GIF 不附带截图元数据、设备标识或服务地址。闹钟标识仅为已取消的验收夹具。

## 重新生成

使用 macOS 自带 Swift、AppKit 与 ImageIO，无第三方媒体依赖。先将验收截图导出至本机目录，并保留生成脚本中列明的相对目录及文件名：

```bash
swift scripts/generate-readme-demos.swift <验收附件根目录> /tmp/companion-readme-previews
```

脚本输出三个 GIF 和每帧 PNG 预览，校验帧数与解码。逐帧复核裁剪、遮挡、文字可读性和素材来源后，仅将 GIF 复制到本目录。裁剪坐标按宽 943 像素的预览归一化；更换截图后必须重新调整和审阅，不能沿用遮挡位置直接发布。
