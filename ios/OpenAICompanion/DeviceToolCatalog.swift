import Foundation
import CoreFoundation

struct DeviceToolSpec {
    let name: String
    let group: String
    let description: String
    var write = false
    var properties: [String: [String: Any]] = [:]
    var required: [String] = []
    var schema: [String: Any] {
        var p = properties
        var r = required
        if write { p["request_id"] = ["type": "string", "minLength": 8, "maxLength": 128, "pattern": "^[A-Za-z0-9_-]{8,128}$"]; r.append("request_id") }
        return ["type": "object", "properties": p, "required": r, "additionalProperties": false]
    }
    var json: [String: Any] { ["name": name, "group": group, "description": description,
                               "write": write, "schema": schema] }
    static func string(_ max: Int = 500) -> [String: Any] { ["type": "string", "minLength": 1, "maxLength": max] }
    static func choice(_ values: [String]) -> [String: Any] { ["type": "string", "enum": values] }
    static func number(_ min: Double, _ max: Double, integer: Bool = false) -> [String: Any] {
        ["type": integer ? "integer" : "number", "minimum": min, "maximum": max]
    }
    func validate(_ args: [String: Any]) throws {
        let schema = self.schema
        let props = schema["properties"] as! [String: [String: Any]]
        guard Set(args.keys).isSubset(of: Set(props.keys)),
              (schema["required"] as! [String]).allSatisfy({ args[$0] != nil }) else {
            throw DeviceFailure("INVALID_ARGUMENTS", "Missing or unknown arguments")
        }
        for (key, value) in args {
            let rule = props[key]!
            switch rule["type"] as? String {
            case "string":
                guard let s = value as? String, s.count >= (rule["minLength"] as? Int ?? 1), s.count <= (rule["maxLength"] as? Int ?? 500),
                      (rule["pattern"] as? String).map({ s.range(of: $0, options: .regularExpression)?.lowerBound == s.startIndex && s.range(of: $0, options: .regularExpression)?.upperBound == s.endIndex }) ?? true,
                      (rule["enum"] as? [String]).map({ $0.contains(s) }) ?? true else {
                    throw DeviceFailure("INVALID_ARGUMENTS", "Invalid \(key)")
                }
            case "number", "integer":
                guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite,
                      n.doubleValue >= (rule["minimum"] as? Double ?? -Double.greatestFiniteMagnitude),
                      n.doubleValue <= (rule["maximum"] as? Double ?? Double.greatestFiniteMagnitude),
                      rule["type"] as? String != "integer" || n.doubleValue.rounded() == n.doubleValue else {
                    throw DeviceFailure("INVALID_ARGUMENTS", "Invalid \(key)")
                }
            default: throw DeviceFailure("INVALID_ARGUMENTS", "Unsupported argument type")
            }
        }
    }
}
struct DeviceFailure: Error {
    let code: String
    let message: String
    init(_ code: String, _ message: String) { self.code = code; self.message = message }
}

let deviceToolCatalog: [DeviceToolSpec] = {
    let text = DeviceToolSpec.string()
    let ref = DeviceToolSpec.string(128)
    let date = DeviceToolSpec.string(40)
    let range = ["start": date, "end": date]
    var tools: [DeviceToolSpec] = []
    func add(_ name: String, _ group: String, _ desc: String, _ props: [String: [String: Any]] = [:],
             _ required: [String] = [], write: Bool = false) {
        tools.append(DeviceToolSpec(name: "device_" + name, group: group, description: desc,
                                    write: write, properties: props, required: required))
    }
    add("health_get_availability", "health", "Check HealthKit support. Does not reveal read authorization.")
    add("health_query_statistics", "health", "Daily HealthKit totals; no readable data is null, never zero. ISO8601 range up to 31 days.",
        range.merging(["metric": DeviceToolSpec.choice(["steps", "walking_running_distance", "active_energy"]), "time_zone": text]) { _, b in b }, ["metric", "start", "end", "time_zone"])
    add("health_list_sleep", "health", "Read sleep samples with source and stages, up to 31 days. Overlapping sources are not summed.", range, ["start", "end"])
    add("health_list_workouts", "health", "Read workouts with sources, up to 31 days.", range, ["start", "end"])
    add("home_list_homes", "home", "List authorized homes. Experimental; real accessories not acceptance-tested.")
    add("home_list_rooms", "home", "List rooms in a discovered home.", ["home_ref": ref], ["home_ref"])
    add("home_list_accessories", "home", "List accessories and supported control properties; returns temporary references.", ["home_ref": ref], ["home_ref"])
    add("home_get_state", "home", "Read current supported accessory characteristics; unavailable values stay explicit.", ["accessory_ref": ref], ["accessory_ref"])
    add("home_set_characteristic", "home", "Set a discovered light or thermostat property. Displays target and value confirmation; no automatic retry.",
        ["characteristic_ref": ref, "value": DeviceToolSpec.number(-100, 1000)], ["characteristic_ref", "value"], write: true)
    add("home_list_scenes", "home", "List scenes with action previews; unsupported or security actions cannot run.", ["home_ref": ref], ["home_ref"])
    add("home_execute_scene", "home", "Confirm all actions and execute a supported scene. Failures may be partial; inspect state before any further action.", ["scene_ref": ref], ["scene_ref"], write: true)
    add("reminder_list_lists", "reminders", "List reminder lists after permission.")
    add("reminder_list_items", "reminders", "List up to 100 reminders in a chosen list with version references.", ["list_ref": ref], ["list_ref"])
    add("reminder_create_item", "reminders", "Create one reminder in a chosen list after confirmation. Optional due date has an explicit timezone offset.",
        ["list_ref": ref, "title": text, "notes": DeviceToolSpec.string(2000), "due": date], ["list_ref", "title"], write: true)
    add("reminder_complete_item", "reminders", "Complete one nonrecurring reminder, requiring unchanged version.", ["item_ref": ref], ["item_ref"], write: true)
    add("calendar_list_editable_events", "calendar_edit", "List up to 100 nonrecurring events for editing with temporary version references. ISO8601 range up to 31 days.", range, ["start", "end"])
    add("calendar_update_event", "calendar_edit", "Reschedule one unchanged, nonrecurring, timed event after showing before/after confirmation.",
        ["event_ref": ref, "start": date, "end": date], ["event_ref", "start", "end"], write: true)
    add("contact_pick", "contacts", "User selects one contact. Returns name and selected contact phone/email fields; does not search the full address book.")
    add("file_pick", "files", "User selects a TXT, Markdown, PDF or audio file. Returns a temporary resource reference, never a filesystem path.")
    add("file_read_text", "files", "Read text/Markdown or one PDF page from a selected resource, at most 12000 characters.",
        ["resource_ref": ref, "offset": DeviceToolSpec.number(0, 1000000, integer: true), "page": DeviceToolSpec.number(1, 500, integer: true)], ["resource_ref"])
    add("photo_pick", "capture", "User selects one photo for local OCR.")
    add("document_scan", "capture", "User scans paper using the system scanner; returns at most 10 image references.")
    add("image_recognize_text", "capture", "Local OCR on a selected image. Returns bounded text blocks, confidence and positions; verify dates before writing.", ["resource_ref": ref], ["resource_ref"])
    add("audio_record", "audio", "Record up to 60 seconds in foreground after explicit recording confirmation; returns local audio resource.", ["seconds": DeviceToolSpec.number(1, 60, integer: true)], ["seconds"])
    add("audio_transcribe", "audio", "Transcribe selected/recorded audio locally only. Fails when on-device recognition or language is unavailable.",
        ["resource_ref": ref, "language": DeviceToolSpec.string(30)], ["resource_ref", "language"])
    if #available(iOS 26.0, *) {
        add("alarm_create", "alarms", "Create an app-owned AlarmKit alarm after confirmation; ISO8601 future time. Requires system authorization.", ["title": text, "time": date], ["title", "time"], write: true)
        add("alarm_list", "alarms", "List alarms owned by this app, not the Clock app.")
        add("alarm_cancel", "alarms", "Cancel an app-owned alarm by ID after confirmation.", ["alarm_id": ref], ["alarm_id"], write: true)
    }
    return tools
}()

func deviceJSON(_ object: Any) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
          let string = String(data: data, encoding: .utf8) else { return "{\"status\":\"error\",\"code\":\"NATIVE_SERIALIZATION_FAILED\",\"message\":\"Native result could not be serialized\"}" }
    return string
}
func deviceDate(_ text: String) throws -> Date {
    let f = ISO8601DateFormatter()
    if let date = f.date(from: text) { return date }
    f.formatOptions.insert(.withFractionalSeconds)
    guard let date = f.date(from: text) else { throw DeviceFailure("INVALID_ARGUMENTS", "Date must be ISO8601 with timezone") }
    return date
}
func deviceRange(_ args: [String: Any], maxDays: Double = 31) throws -> (Date, Date) {
    let start = try deviceDate(args["start"] as! String), end = try deviceDate(args["end"] as! String)
    guard end > start, end.timeIntervalSince(start) <= maxDays * 86400 else { throw DeviceFailure("INVALID_ARGUMENTS", "Invalid or excessive time range") }
    return (start, end)
}
