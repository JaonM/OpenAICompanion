import Foundation
import AlarmKit
import SwiftUI

@available(iOS 26.0, *)
private struct CompanionAlarmMetadata: AlarmMetadata { let title: String }

extension DeviceToolsBridge {
    func alarmTool(_ name: String, _ args: [String: Any]) async throws -> [String: Any] {
        guard #available(iOS 26.0, *) else { throw DeviceFailure("UNSUPPORTED_OS", "AlarmKit requires iOS 26 or later") }
        let manager = AlarmManager.shared
        guard try await manager.requestAuthorization() == .authorized else { throw DeviceFailure("PERMISSION_DENIED", "Allow alarms in Settings") }
        try checkForeground()
        if name == "device_alarm_list" {
            return ["alarms": try manager.alarms.map { ["alarm_id": $0.id.uuidString, "state": String(describing: $0.state)] }, "scope": "this_app_only"]
        }
        if name == "device_alarm_cancel" {
            guard let id = UUID(uuidString: args["alarm_id"] as! String), try manager.alarms.contains(where: { $0.id == id }) else {
                throw DeviceFailure("ALARM_NOT_FOUND", "List app alarms again")
            }
            try await interaction.confirm("取消闹钟", id.uuidString)
            try manager.cancel(id: id)
            return ["cancelled": true, "alarm_id": id.uuidString]
        }
        let time = try deviceDate(args["time"] as! String)
        guard time > Date(), time.timeIntervalSinceNow <= 366 * 86400 else { throw DeviceFailure("INVALID_ARGUMENTS", "Choose a future time within one year") }
        let title = args["title"] as! String
        try await interaction.confirm("创建闹钟", "\(title)\n\(args["time"] as! String)")
        let attributes = AlarmAttributes(presentation: AlarmPresentation(alert: AlarmPresentation.Alert(
            title: LocalizedStringResource(stringLiteral: title),
            stopButton: AlarmButton(text: "停止", textColor: .white, systemImageName: "stop.circle"))),
            metadata: CompanionAlarmMetadata(title: title), tintColor: .blue)
        let id = UUID()
        _ = try await manager.schedule(id: id, configuration: .init(schedule: .fixed(time), attributes: attributes))
        return ["scheduled": true, "alarm_id": id.uuidString, "title": title, "time": args["time"]!]
    }
}
