import Foundation
import HomeKit
import UIKit

@main struct DeviceToolsTests {
    static func rejects(_ block: () throws -> Void) {
        do { try block(); fatalError("Expected rejection") } catch { }
    }
    @MainActor static func main() async throws {
        let catalog = deviceToolCatalog
        precondition(Set(catalog.map(\.name)).count == catalog.count)
        precondition(catalog.filter { $0.group == "health" }.allSatisfy { !$0.write })
        let write = catalog.first { $0.name == "device_home_set_characteristic" }!
        try write.validate(["request_id": "native-test-001", "characteristic_ref": "lamp", "value": 30])
        for invalid in ["short", "invalid id", "中文request001", "request001\n"] {
            rejects { try write.validate(["request_id": invalid, "characteristic_ref": "lamp", "value": 30]) }
        }
        rejects { try write.validate(["request_id": "native-test-001", "characteristic_ref": "lamp", "value": true]) }
        rejects { try write.validate(["request_id": "native-test-001", "characteristic_ref": "lamp", "value": 30, "extra": "bad"]) }
        rejects { try write.validate(["characteristic_ref": "lamp", "value": 30]) }
        rejects { _ = try deviceRange(["start": "2026-10-01T00:00:00Z", "end": "2026-09-01T00:00:00Z"]) }
        rejects { _ = try deviceRange(["start": "2026-10-01T00:00:00Z", "end": "2027-09-01T00:00:00Z"]) }
        rejects { _ = try deviceDate("2026-10-01T00:00:00") }
        precondition(DeviceHomePolicy.allows(service: HMServiceTypeLightbulb, characteristic: HMCharacteristicTypeBrightness))
        precondition(!DeviceHomePolicy.allows(service: HMServiceTypeLockMechanism, characteristic: HMCharacteristicTypeTargetLockMechanismState))
        precondition(!DeviceHomePolicy.allows(service: HMServiceTypeGarageDoorOpener, characteristic: HMCharacteristicTypeTargetDoorState))
        rejects { try DeviceHomePolicy.validate(value: 101, minimum: 0, maximum: 100, step: 1, validValues: nil) }
        rejects { try DeviceHomePolicy.validate(value: 20.5, minimum: 0, maximum: 100, step: 1, validValues: nil) }
        rejects { try DeviceHomePolicy.validate(value: 5, minimum: nil, maximum: nil, step: nil, validValues: [0, 1]) }
        try DeviceHomePolicy.validate(value: 30, minimum: 0, maximum: 100, step: 1, validValues: nil)
        precondition(deviceTimeoutResult(writes: false)["code"] as? String == "INTERACTION_TIMEOUT")
        precondition(deviceTimeoutResult(writes: true)["code"] as? String == "OUTCOME_UNKNOWN")
        var completed = 0
        let invocation = DeviceInvocation { _ in completed += 1 }
        invocation.finish(["status": "ok"]); invocation.finish(["status": "error"])
        precondition(completed == 1)
        let resources = DeviceResources()
        let url = resources.allocate(extension: "txt")
        try Data("中文测试".utf8).write(to: url)
        let resource = try resources.add(url)
        let resolved = try resources.resolve(resource["resource_ref"] as! String)
        precondition(resolved == url)
        rejects { _ = try resources.resolve("../../etc/passwd") }
        let bridge = DeviceToolsBridge()
        try await bridge.acquire("first")
        let queued = Task { @MainActor in try await bridge.acquire("second") }
        try await Task.sleep(nanoseconds: 75_000_000)
        precondition(bridge.activeCall == "first")
        bridge.release("first")
        try await queued.value
        bridge.release("first")
        precondition(bridge.activeCall == "second")
        bridge.release("second")
        let textURL = bridge.resources.allocate(extension: "txt")
        try Data("会议记录\n行动项：提交方案".utf8).write(to: textURL)
        let textRef = try bridge.resources.add(textURL)["resource_ref"] as! String
        let read = try await bridge.resourceTool("device_file_read_text", ["resource_ref": textRef, "offset": 5])
        precondition(read["text"] as? String == "行动项：提交方案")
        let pdfURL = bridge.resources.allocate(extension: "pdf")
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 400))
        try pdf.writePDF(to: pdfURL) { context in
            context.beginPage()
            ("Companion PDF" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
        }
        let pdfRef = try bridge.resources.add(pdfURL)["resource_ref"] as! String
        let pdfRead = try await bridge.resourceTool("device_file_read_text", ["resource_ref": pdfRef])
        precondition((pdfRead["text"] as? String)?.contains("Companion PDF") == true)
        precondition(pdfRead["page_count"] as? Int == 1)
        let imageURL = bridge.resources.allocate(extension: "png")
        let image = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 180)).image { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 800, height: 180))
            ("COMPANION 2026" as NSString).draw(at: CGPoint(x: 30, y: 40), withAttributes: [.font: UIFont.systemFont(ofSize: 56), .foregroundColor: UIColor.black])
        }
        try image.pngData()!.write(to: imageURL)
        let imageRef = try bridge.resources.add(imageURL)["resource_ref"] as! String
        let recognized = try await bridge.resourceTool("device_image_recognize_text", ["resource_ref": imageRef])
        let blocks = recognized["blocks"] as! [[String: Any]]
        precondition(blocks.contains { ($0["text"] as? String)?.contains("COMPANION") == true })
        print("DeviceToolsTests passed: catalog, parameter types, date limits, household allowlist, value bounds, callback-once, serialized calls, opaque resource access, UTF-8 slices, PDF extraction, real Vision OCR")
    }
}
