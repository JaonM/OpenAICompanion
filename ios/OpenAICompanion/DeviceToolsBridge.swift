import Foundation
import UIKit
import HealthKit
import HomeKit
import EventKit

@_cdecl("oc_device_catalog")
func ocDeviceCatalog() -> UnsafeMutablePointer<CChar>? { strdup(deviceJSON(deviceToolCatalog.map(\.json))) }

@_cdecl("oc_device_call")
func ocDeviceCall(_ id: UnsafePointer<CChar>, _ name: UnsafePointer<CChar>, _ args: UnsafePointer<CChar>,
                  _ callback: @escaping @convention(c) (UnsafePointer<CChar>?, UnsafeMutableRawPointer?) -> Void,
                  _ context: UnsafeMutableRawPointer?) {
    let callID = String(cString: id), tool = String(cString: name), arguments = String(cString: args)
    DispatchQueue.main.async {
        let bridge = DeviceToolsBridge.shared
        let invocation = DeviceInvocation { result in
            deviceJSON(result).withCString { callback($0, context) }
        }
        bridge.calls[callID] = invocation
        let task = Task { @MainActor in
            let result: [String: Any]
            do {
                try await bridge.acquire(callID)
                defer { bridge.release(callID) }
                try Task.checkCancellation()
                guard UIApplication.shared.applicationState == .active else { throw DeviceFailure("FOREGROUND_REQUIRED", "Return to the app") }
                guard let spec = deviceToolCatalog.first(where: { $0.name == tool }),
                      UserDefaults.standard.bool(forKey: "deviceGroup.\(spec.group)"),
                      UserDefaults.standard.bool(forKey: "deviceToolsEnabled") else { throw DeviceFailure("TOOL_UNAVAILABLE", "Tool group disabled") }
                let args = try JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Any] ?? [:]
                try spec.validate(args)
                let data = try await bridge.execute(tool, args)
                guard deviceJSON(data).utf8.count <= 32768 else { throw DeviceFailure("RESULT_TOO_LARGE", "Narrow the query; result exceeds 32 KiB") }
                if !spec.write {
                    try Task.checkCancellation()
                    guard UIApplication.shared.applicationState == .active,
                          UserDefaults.standard.bool(forKey: "deviceToolsEnabled"),
                          UserDefaults.standard.bool(forKey: "deviceGroup.\(spec.group)") else {
                        throw DeviceFailure("FOREGROUND_REQUIRED", "Tool disabled or app left foreground")
                    }
                }
                result = ["status": "ok", "data": data]
            } catch let e as DeviceFailure {
                result = ["status": "requires_user_action", "code": e.code, "message": e.message]
            } catch is CancellationError {
                result = ["status": "requires_user_action", "code": "CANCELLED", "message": "Operation cancelled"]
            } catch {
                result = ["status": "error", "code": "NATIVE_OPERATION_FAILED", "message": error.localizedDescription]
            }
            if bridge.calls[callID] === invocation { bridge.calls.removeValue(forKey: callID) }
            invocation.finish(result)
        }
        invocation.task = task
        // Bounds system permission waits and user interaction, without repeating a side effect.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 90_000_000_000)
            if bridge.calls[callID] === invocation {
                task.cancel()
                if bridge.activeCall == callID { bridge.interaction.cancel() }
                bridge.release(callID)
                bridge.calls.removeValue(forKey: callID)
                invocation.finish(deviceTimeoutResult(writes: deviceToolCatalog.first(where: { $0.name == tool })?.write == true))
            }
        }
    }
}
@_cdecl("oc_device_cancel")
func ocDeviceCancel(_ id: UnsafePointer<CChar>) {
    let callID = String(cString: id)
    DispatchQueue.main.async {
        let bridge = DeviceToolsBridge.shared
        guard let invocation = bridge.calls.removeValue(forKey: callID) else { return }
        invocation.task?.cancel()
        if bridge.activeCall == callID { bridge.interaction.cancel() }
        bridge.release(callID)
        invocation.finish(["status": "requires_user_action", "code": "CANCELLED", "message": "Operation cancelled"])

    }
}

func deviceTimeoutResult(writes: Bool) -> [String: Any] {
    if writes {
        return ["status": "error", "code": "OUTCOME_UNKNOWN", "message": "Native operation timed out. A write may have executed; inspect state before any further action."]
    }
    return ["status": "requires_user_action", "code": "INTERACTION_TIMEOUT",
            "message": "Native read or interaction timed out. Close any system sheet, then try again."]
}

@MainActor final class DeviceInvocation {
    var task: Task<Void, Never>?
    private var callback: (([String: Any]) -> Void)?
    init(_ callback: @escaping ([String: Any]) -> Void) { self.callback = callback }
    func finish(_ result: [String: Any]) {
        let callback = self.callback; self.callback = nil
        callback?(result)
    }
}

@MainActor final class DeviceToolsBridge {
    static let shared = DeviceToolsBridge()
    var calls: [String: DeviceInvocation] = [:]
    private(set) var activeCall: String?
    func acquire(_ id: String) async throws {
        while activeCall != nil {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        try Task.checkCancellation()
        activeCall = id
    }
    func release(_ id: String) { if activeCall == id { activeCall = nil } }
    let health = HKHealthStore()
    let home = DeviceHomeSource()
    let events = EKEventStore()
    let interaction = DeviceInteraction()
    let resources = DeviceResources()
    var eventRefs: [String: (id: String, version: String, expires: Date)] = [:]
    var reminderRefs: [String: (id: String, version: String, expires: Date)] = [:]
    var listRefs: [String: (id: String, title: String, expires: Date)] = [:]

    func execute(_ name: String, _ args: [String: Any]) async throws -> [String: Any] {
        if name.hasPrefix("device_health_") { return try await healthTool(name, args) }
        if name.hasPrefix("device_home_") { return try await home.execute(name, args, interaction: interaction) }
        if name.hasPrefix("device_reminder_") || name.hasPrefix("device_calendar_") { return try await eventTool(name, args) }
        if name.hasPrefix("device_alarm_") { return try await alarmTool(name, args) }
        return try await resourceTool(name, args)
    }
    func checkForeground() throws {
        try Task.checkCancellation()
        guard UIApplication.shared.applicationState == .active else { throw DeviceFailure("FOREGROUND_REQUIRED", "Return to the app before continuing") }
    }
}

@MainActor final class DeviceInteraction: NSObject, UIAdaptivePresentationControllerDelegate {
    private var completion: ((Result<Any, Error>) -> Void)?
    private weak var presented: UIViewController?
    var retainedDelegate: AnyObject?
    private(set) var generation = 0
    var isActive: Bool { completion != nil }
    func presenter() throws -> UIViewController {
        guard UIApplication.shared.applicationState == .active,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var vc = scene.windows.first(where: \.isKeyWindow)?.rootViewController else {
            throw DeviceFailure("FOREGROUND_REQUIRED", "Open the app")
        }
        while let next = vc.presentedViewController { vc = next }
        return vc
    }
    func present(_ vc: UIViewController) async throws -> Any {
        guard completion == nil else { throw DeviceFailure("INTERACTION_BUSY", "Finish the current system interaction first") }
        try Task.checkCancellation()
        let parent = try presenter()
        generation += 1
        let expected = generation
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                completion = { continuation.resume(with: $0) }
                presented = vc
                parent.present(vc, animated: true)
                // UIAlertController owns its presentation delegate and asserts if replaced.
                // Alert actions resolve the continuation; sheet pickers also support swipe dismissal.
                if !(vc is UIAlertController) { vc.presentationController?.delegate = self }
            }
        }, onCancel: { Task { @MainActor in self.finish(.failure(CancellationError()), generation: expected) } })
    }
    func finish(_ result: Result<Any, Error>, generation expected: Int? = nil) {
        if let expected, expected != generation { return }
        guard let callback = completion else { return }
        completion = nil
        presented?.dismiss(animated: true)
        presented = nil
        retainedDelegate = nil
        callback(result)
    }
    func cancel() { finish(.failure(DeviceFailure("USER_CANCELLED", "User interaction cancelled"))) }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        // A previous sheet can finish its dismissal after the next interaction starts.
        guard presented === presentationController.presentedViewController else { return }
        cancel()
    }
    func confirm(_ title: String, _ message: String) async throws {
        let expected = generation + 1
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in self.finish(.failure(DeviceFailure("USER_CANCELLED", "Cancelled")), generation: expected) })
        alert.addAction(UIAlertAction(title: "确认", style: .default) { _ in self.finish(.success(true), generation: expected) })
        _ = try await present(alert)
        try Task.checkCancellation()
        guard UIApplication.shared.applicationState == .active else { throw DeviceFailure("FOREGROUND_REQUIRED", "Return to app") }
    }
}
