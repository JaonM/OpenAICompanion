import Foundation
import HomeKit

/// Pure policy used both for individual writes and every action in a scene.
enum DeviceHomePolicy {
    static let allowed: [String: Set<String>] = [
        HMServiceTypeLightbulb: [HMCharacteristicTypePowerState, HMCharacteristicTypeBrightness],
        HMServiceTypeThermostat: [HMCharacteristicTypeTargetTemperature, HMCharacteristicTypeTargetHeatingCooling]
    ]
    static func allows(service: String, characteristic: String) -> Bool {
        allowed[service]?.contains(characteristic) == true
    }
    static func validate(value: Double, minimum: Double?, maximum: Double?, step: Double?, validValues: [Double]?) throws {
        guard value.isFinite, minimum.map({ value >= $0 }) ?? true,
              maximum.map({ value <= $0 }) ?? true,
              validValues.map({ $0.contains(value) }) ?? true else { throw DeviceFailure("INVALID_VALUE", "Value outside accessory constraints") }
        if let step, step > 0 {
            let steps = (value - (minimum ?? 0)) / step
            guard abs(steps - steps.rounded()) < 0.00001 else { throw DeviceFailure("INVALID_VALUE", "Value does not match accessory step") }
        }
    }
}

@MainActor final class DeviceHomeSource: NSObject, HMHomeManagerDelegate {
    private var manager: HMHomeManager?
    private var loaded = false
    private var refs: [String: (object: AnyObject, expires: Date)] = [:]
    nonisolated func homeManagerDidUpdateHomes(_ manager: HMHomeManager) { Task { @MainActor in self.loaded = true } }
    private func ready() async throws -> HMHomeManager {
        if manager == nil { manager = HMHomeManager(); manager?.delegate = self }
        for _ in 0..<100 {
            try Task.checkCancellation()
            if loaded { return manager! }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw DeviceFailure("HOME_NOT_READY", "Home authorization or discovery incomplete. Check Home settings and retry.")
    }
    private func ref(_ object: AnyObject) -> String {
        refs = refs.filter { $0.value.expires > Date() }
        let key = UUID().uuidString
        refs[key] = (object, Date().addingTimeInterval(300))
        return key
    }
    private func resolve<T>(_ key: String, as type: T.Type) throws -> T {
        guard let entry = refs[key], entry.expires > Date(), let object = entry.object as? T else {
            throw DeviceFailure("REFERENCE_EXPIRED", "Discover home devices again")
        }
        return object
    }
    private func supported(_ c: HMCharacteristic) -> Bool {
        guard let s = c.service else { return false }
        return DeviceHomePolicy.allows(service: s.serviceType, characteristic: c.characteristicType) && c.properties.contains(HMCharacteristicPropertyWritable)
    }
    private func validate(_ c: HMCharacteristic, value: Double) throws {
        guard supported(c), c.service?.accessory?.isReachable == true else { throw DeviceFailure("UNSUPPORTED_OR_OFFLINE", "Only reachable lights and thermostats can be controlled") }
        if c.characteristicType == HMCharacteristicTypePowerState, value != 0 && value != 1 {
            throw DeviceFailure("INVALID_VALUE", "Power must be 0 or 1")
        }
        let m = c.metadata
        if c.characteristicType == HMCharacteristicTypeBrightness {
            try DeviceHomePolicy.validate(value: value, minimum: 0, maximum: 100, step: 1, validValues: nil)
        }
        if c.characteristicType == HMCharacteristicTypeTargetHeatingCooling {
            try DeviceHomePolicy.validate(value: value, minimum: 0, maximum: 3, step: 1, validValues: nil)
        }
        if c.characteristicType == HMCharacteristicTypeTargetTemperature,
           (m?.minimumValue == nil || m?.maximumValue == nil) {
            throw DeviceFailure("METADATA_UNAVAILABLE", "Temperature control requires accessory bounds")
        }
        try DeviceHomePolicy.validate(value: value, minimum: m?.minimumValue?.doubleValue,
            maximum: m?.maximumValue?.doubleValue, step: m?.stepValue?.doubleValue,
            validValues: m?.validValues?.map { $0.doubleValue })
    }
    private func summary(_ c: HMCharacteristic) -> [String: Any] {
        let m = c.metadata
        return ["characteristic_ref": ref(c), "type": c.characteristicType,
                "description": c.localizedDescription, "room": c.service?.accessory?.room?.name ?? "",
                "accessory": c.service?.accessory?.name ?? "", "writable": supported(c),
                "minimum": m?.minimumValue as Any? ?? NSNull(), "maximum": m?.maximumValue as Any? ?? NSNull(),
                "step": m?.stepValue as Any? ?? NSNull(), "unit": m?.units as Any? ?? NSNull(),
                "valid_values": m?.validValues as Any? ?? NSNull()]
    }
    private func sceneActions(_ scene: HMActionSet) throws -> [(HMCharacteristic, Double)] {
        guard !scene.actions.isEmpty, scene.actions.count <= 30 else { throw DeviceFailure("SCENE_UNSUPPORTED", "Empty or excessive scene") }
        return try scene.actions.map { action in
            guard let action = action as? HMCharacteristicWriteAction<NSNumber> else { throw DeviceFailure("SCENE_UNSUPPORTED", "Scene contains unsupported actions") }
            try validate(action.characteristic, value: action.targetValue.doubleValue)
            return (action.characteristic, action.targetValue.doubleValue)
        }
    }
    private func fingerprint(_ actions: [(HMCharacteristic, Double)]) -> String {
        actions.map { "\($0.0.uniqueIdentifier):\($0.1)" }.sorted().joined(separator: "|")
    }
    func execute(_ name: String, _ args: [String: Any], interaction: DeviceInteraction) async throws -> [String: Any] {
        let manager = try await ready()
        guard manager.authorizationStatus.contains(.authorized) else { throw DeviceFailure("PERMISSION_DENIED", "Allow Home access in Settings") }
        if name == "device_home_list_homes" {
            return ["homes": manager.homes.map { ["home_ref": ref($0), "name": $0.name] },
                    "acceptance": "real_accessories_not_verified", "empty_reason": "No configured or accessible homes if list is empty"]
        }
        if name == "device_home_get_state" {
            let accessory = try resolve(args["accessory_ref"] as! String, as: HMAccessory.self)
            guard manager.homes.contains(where: { $0.accessories.contains(accessory) }) else { throw DeviceFailure("DEVICE_REMOVED", "Discover devices again") }
            var values: [[String: Any]] = []
            let readable = accessory.services.flatMap(\.characteristics).filter { $0.properties.contains(HMCharacteristicPropertyReadable) && ($0.service.map { DeviceHomePolicy.allowed[$0.serviceType] != nil } ?? false) }
            for c in readable.prefix(30) {
                var row = summary(c)
                do {
                    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                        c.readValue { error in if let error { cont.resume(throwing: error) } else { cont.resume() } }
                    }
                    let value = c.value ?? NSNull()
                    if JSONSerialization.isValidJSONObject(["value": value]) {
                        row["value"] = value; row["status"] = "ok"
                    } else { row["value"] = NSNull(); row["status"] = "unsupported_value_format" }
                } catch { row["status"] = "unavailable" }
                values.append(row)
            }
            return ["name": accessory.name, "reachable": accessory.isReachable, "characteristics": values, "truncated": readable.count > 30]
        }
        if name == "device_home_set_characteristic" {
            let c = try resolve(args["characteristic_ref"] as! String, as: HMCharacteristic.self)
            let value = (args["value"] as! NSNumber).doubleValue
            guard let targetHome = manager.homes.first(where: { home in home.accessories.contains(where: { $0 === c.service?.accessory }) }) else { throw DeviceFailure("DEVICE_REMOVED", "Discover devices again") }
            try validate(c, value: value)
            let description = "\(targetHome.name) / \(c.service?.accessory?.room?.name ?? "") / \(c.service?.accessory?.name ?? "")\n\(c.localizedDescription) → \(value)"
            try await interaction.confirm("控制家庭设备", description)
            guard manager.authorizationStatus.contains(.authorized),
                  manager.homes.contains(targetHome), targetHome.accessories.contains(where: { $0 === c.service?.accessory }) else {
                throw DeviceFailure("DEVICE_REMOVED", "Home access or device changed during confirmation")
            }
            try validate(c, value: value)
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                c.writeValue(NSNumber(value: value)) { error in if let error { cont.resume(throwing: error) } else { cont.resume() } }
            }
            return ["write_acknowledged": true, "physical_state_verified": false, "requested_value": value,
                    "next_step": "Use home_get_state to observe current state; acknowledgement does not verify physical behavior."]
        }
        if name == "device_home_execute_scene" {
            let scene = try resolve(args["scene_ref"] as! String, as: HMActionSet.self)
            guard let home = manager.homes.first(where: { $0.actionSets.contains(scene) }) else { throw DeviceFailure("SCENE_REMOVED", "List scenes again") }
            let actions = try sceneActions(scene)
            let snapshot = fingerprint(actions)
            try await interaction.confirm("执行场景：\(home.name) / \(scene.name)", actions.map {
                "\($0.0.service?.accessory?.name ?? "") / \($0.0.localizedDescription) → \($0.1)"
            }.joined(separator: "\n"))
            guard manager.authorizationStatus.contains(.authorized), manager.homes.contains(home), home.actionSets.contains(scene), fingerprint(try sceneActions(scene)) == snapshot else { throw DeviceFailure("SCENE_CHANGED", "Scene changed; list and confirm again") }
            do {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                    home.executeActionSet(scene) { error in if let error { cont.resume(throwing: error) } else { cont.resume() } }
                }
            } catch { throw DeviceFailure("SCENE_OUTCOME_UNKNOWN", "Scene may have partially executed. Inspect device state; do not automatically repeat.") }
            return ["scene": scene.name, "execution_acknowledged": true, "physical_state_verified": false]
        }
        let home = try resolve(args["home_ref"] as! String, as: HMHome.self)
        guard manager.homes.contains(home) else { throw DeviceFailure("HOME_REMOVED", "Discover homes again") }
        if name == "device_home_list_rooms" {
            return ["rooms": home.rooms.map { ["room_ref": ref($0), "name": $0.name] }]
        }
        if name == "device_home_list_scenes" {
            return ["scenes": home.actionSets.map { scene -> [String: Any] in
                let actions = try? sceneActions(scene)
                return ["scene_ref": ref(scene), "name": scene.name, "supported": actions != nil,
                        "actions": actions?.map { ["accessory": $0.0.service?.accessory?.name ?? "", "property": $0.0.localizedDescription, "value": $0.1] } ?? [],
                        "unsupported_reason": actions == nil ? "Unsupported, security-related, offline or excessive actions" : ""]
            }]
        }
        return ["accessories": home.accessories.map { a in
            ["accessory_ref": ref(a), "name": a.name, "room": a.room?.name ?? "", "reachable": a.isReachable,
             "controls": a.services.flatMap(\.characteristics).filter(supported).map(summary)] as [String: Any]
        }]
    }
}
