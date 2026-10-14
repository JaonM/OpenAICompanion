import Foundation
import EventKit

extension DeviceToolsBridge {
    private func eventVersion(_ event: EKEvent) -> String {
        deviceJSON(["title": event.title ?? "", "start": event.startDate.timeIntervalSince1970,
                    "end": event.endDate.timeIntervalSince1970, "modified": event.lastModifiedDate?.timeIntervalSince1970 ?? 0,
                    "calendar": event.calendar.calendarIdentifier, "all_day": event.isAllDay])
    }
    private func reminderVersion(_ item: EKReminder) -> String {
        deviceJSON(["title": item.title ?? "", "completed": item.isCompleted,
                    "modified": item.lastModifiedDate?.timeIntervalSince1970 ?? 0,
                    "calendar": item.calendar.calendarIdentifier])
    }
    func eventTool(_ name: String, _ args: [String: Any]) async throws -> [String: Any] {
        if name.hasPrefix("device_calendar_") {
            guard try await events.requestFullAccessToEvents() else { throw DeviceFailure("PERMISSION_DENIED", "Allow calendar access in Settings") }
            try checkForeground()
            if name == "device_calendar_list_editable_events" {
                let (start, end) = try deviceRange(args)
                let matches = events.events(matching: events.predicateForEvents(withStart: start, end: end, calendars: nil))
                    .filter { !$0.hasRecurrenceRules && !$0.isAllDay && $0.calendar.allowsContentModifications }
                guard matches.count <= 100 else { throw DeviceFailure("RANGE_TOO_LARGE", "Narrow date range to at most 100 editable events") }
                eventRefs = eventRefs.filter { $0.value.expires > Date() }
                let rows: [[String: Any]] = matches.map { event in
                    let ref = UUID().uuidString
                    eventRefs[ref] = (event.eventIdentifier, eventVersion(event), Date().addingTimeInterval(300))
                    return ["event_ref": ref, "title": event.title ?? "", "calendar": event.calendar.title,
                            "start": ISO8601DateFormatter().string(from: event.startDate),
                            "end": ISO8601DateFormatter().string(from: event.endDate)]
                }
                return ["events": rows, "scope": "writable_nonrecurring_timed_events"]
            }
            let (start, end) = try deviceRange(args, maxDays: 367)
            guard let ref = eventRefs[args["event_ref"] as! String], ref.expires > Date(),
                  let event = events.event(withIdentifier: ref.id), eventVersion(event) == ref.version,
                  !event.hasRecurrenceRules, !event.isAllDay, event.calendar.allowsContentModifications else {
                throw DeviceFailure("EVENT_CHANGED", "Event expired, changed or unsupported. Query and confirm again")
            }
            try await interaction.confirm("修改日程", "\(event.calendar.title) / \(event.title ?? "")\n\(event.startDate.description) → \(start.description)\n结束：\(end.description)")
            guard event.refresh(), eventVersion(event) == ref.version else { throw DeviceFailure("EVENT_CHANGED", "Event changed during confirmation") }
            try checkForeground()
            event.startDate = start; event.endDate = end
            try events.save(event, span: .thisEvent, commit: true)
            return ["updated": true, "title": event.title ?? "", "start": args["start"]!, "end": args["end"]!]
        }
        guard try await events.requestFullAccessToReminders() else { throw DeviceFailure("PERMISSION_DENIED", "Allow reminder access in Settings") }
        try checkForeground()
        if name == "device_reminder_list_lists" {
            listRefs = listRefs.filter { $0.value.expires > Date() }
            return ["lists": events.calendars(for: .reminder).map { list -> [String: Any] in
                let ref = UUID().uuidString
                listRefs[ref] = (list.calendarIdentifier, list.title, Date().addingTimeInterval(300))
                return ["list_ref": ref, "title": list.title, "writable": list.allowsContentModifications]
            }]
        }
        if name == "device_reminder_complete_item" {
            guard let ref = reminderRefs[args["item_ref"] as! String], ref.expires > Date(),
                  let item = events.calendarItem(withIdentifier: ref.id) as? EKReminder,
                  reminderVersion(item) == ref.version, !item.hasRecurrenceRules, item.calendar.allowsContentModifications else {
                throw DeviceFailure("REMINDER_CHANGED", "Reminder changed or recurring; list again")
            }
            try await interaction.confirm("完成提醒事项", "\(item.calendar.title) / \(item.title ?? "")")
            guard let current = events.calendarItem(withIdentifier: ref.id) as? EKReminder,
                  reminderVersion(current) == ref.version else { throw DeviceFailure("REMINDER_CHANGED", "Reminder changed during confirmation") }
            try checkForeground()
            current.isCompleted = true
            try events.save(current, commit: true)
            return ["completed": true, "title": current.title ?? ""]
        }
        guard let ref = listRefs[args["list_ref"] as! String], ref.expires > Date(),
              let list = events.calendar(withIdentifier: ref.id), list.title == ref.title else {
            throw DeviceFailure("LIST_CHANGED", "List reminder lists again")
        }
        if name == "device_reminder_list_items" {
            let items: [EKReminder] = await withCheckedContinuation { continuation in
                events.fetchReminders(matching: events.predicateForReminders(in: [list])) { continuation.resume(returning: $0 ?? []) }
            }
            guard items.count <= 100 else { throw DeviceFailure("LIST_TOO_LARGE", "Choose a list with at most 100 reminders") }
            reminderRefs = reminderRefs.filter { $0.value.expires > Date() }
            return ["items": items.map { item -> [String: Any] in
                let ref = UUID().uuidString
                reminderRefs[ref] = (item.calendarItemIdentifier, reminderVersion(item), Date().addingTimeInterval(300))
                return ["item_ref": ref, "title": item.title ?? "", "completed": item.isCompleted,
                        "recurring": item.hasRecurrenceRules, "due": item.dueDateComponents?.date.map { ISO8601DateFormatter().string(from: $0) } as Any? ?? NSNull()]
            }]
        }
        guard list.allowsContentModifications else { throw DeviceFailure("READ_ONLY", "Choose a writable reminder list") }
        let due = try (args["due"] as? String).map(deviceDate)
        try await interaction.confirm("创建提醒事项", "\(list.title)\n\(args["title"] as! String)\n截止：\(args["due"] as? String ?? "未设置")\n\(args["notes"] as? String ?? "")")
        try checkForeground()
        guard let currentList = events.calendar(withIdentifier: ref.id), currentList.title == ref.title,
              currentList.allowsContentModifications else { throw DeviceFailure("LIST_CHANGED", "Reminder list changed") }
        let item = EKReminder(eventStore: events)
        item.calendar = currentList; item.title = args["title"] as? String; item.notes = args["notes"] as? String
        if let due {
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            item.dueDateComponents = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second, .timeZone], from: due)
        }
        // Durable shared journal blocks duplicate execution after an ambiguous save.
        try events.save(item, commit: true)
        return ["created": true, "title": item.title ?? "", "list": list.title]
    }
}
