import Foundation
import UIKit
import UniformTypeIdentifiers
import PhotosUI
import VisionKit
import Vision
import PDFKit
import ContactsUI
import AVFoundation
import Speech

@MainActor final class DeviceResources {
    private var entries: [String: (url: URL, expires: Date)] = [:]
    private let root = FileManager.default.temporaryDirectory.appendingPathComponent("companion-tool-resources", isDirectory: true)
    init() {
        try? FileManager.default.removeItem(at: root)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    func allocate(extension ext: String) -> URL { root.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext) }
    func add(_ url: URL) throws -> [String: Any] {
        for (key, entry) in entries where entry.expires <= Date() {
            try? FileManager.default.removeItem(at: entry.url); entries.removeValue(forKey: key)
        }
        guard entries.count < 20 else { throw DeviceFailure("RESOURCE_LIMIT", "Too many temporary files; wait for expiry") }
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard bytes <= 25 * 1024 * 1024 else { try? FileManager.default.removeItem(at: url); throw DeviceFailure("FILE_TOO_LARGE", "Maximum resource size is 25 MiB") }
        let ref = UUID().uuidString
        entries[ref] = (url, Date().addingTimeInterval(600))
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000_000)
            guard let self, let entry = self.entries.removeValue(forKey: ref) else { return }
            try? FileManager.default.removeItem(at: entry.url)
        }
        return ["resource_ref": ref, "type": url.pathExtension, "bytes": bytes, "expires_in_seconds": 600]
    }
    func resolve(_ ref: String) throws -> URL {
        guard let entry = entries[ref], entry.expires > Date() else { throw DeviceFailure("RESOURCE_EXPIRED", "Select the resource again") }
        return entry.url
    }
    func copy(_ source: URL) throws -> URL {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let target = allocate(extension: source.pathExtension)
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { coordinated in
            do {
                let size = try coordinated.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 25 * 1024 * 1024 else { throw DeviceFailure("FILE_TOO_LARGE", "Maximum resource size is 25 MiB") }
                try FileManager.default.copyItem(at: coordinated, to: target)
            } catch { copyError = error }
        }
        if let error = coordinationError ?? copyError as NSError? { throw error }
        return target
    }
}

@MainActor private struct DevicePresentation {
    let interaction: DeviceInteraction
    let generation: Int
    init(_ interaction: DeviceInteraction) { self.interaction = interaction; generation = interaction.generation + 1 }
    var isCurrent: Bool { interaction.generation == generation && interaction.isActive }
    func finish(_ result: Result<Any, Error>) { interaction.finish(result, generation: generation) }
    func cancel() { finish(.failure(DeviceFailure("USER_CANCELLED", "User cancelled"))) }
}

@MainActor private final class DocumentDelegate: NSObject, UIDocumentPickerDelegate {
    let interaction: DevicePresentation
    let resources: DeviceResources
    init(_ interaction: DeviceInteraction, _ resources: DeviceResources) { self.interaction = DevicePresentation(interaction); self.resources = resources }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { interaction.cancel() }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        do {
            guard let url = urls.first else { interaction.cancel(); return }
            interaction.finish(.success(try resources.add(resources.copy(url))))
        } catch { interaction.finish(.failure(error)) }
    }
}
@MainActor private final class PhotoDelegate: NSObject, PHPickerViewControllerDelegate {
    let interaction: DevicePresentation
    let resources: DeviceResources
    init(_ interaction: DeviceInteraction, _ resources: DeviceResources) { self.interaction = DevicePresentation(interaction); self.resources = resources }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard let result = results.first else { interaction.cancel(); return }
        let target = resources.allocate(extension: "jpg")
        result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
            do {
                if let error { throw error }
                guard let url else { throw DeviceFailure("IMAGE_UNAVAILABLE", "Image unavailable") }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 25 * 1024 * 1024 else { throw DeviceFailure("FILE_TOO_LARGE", "Maximum image size is 25 MiB") }
                try FileManager.default.copyItem(at: url, to: target)
                Task { @MainActor in
                    guard self.interaction.isCurrent else { try? FileManager.default.removeItem(at: target); return }
                    do { self.interaction.finish(.success(try self.resources.add(target))) }
                    catch { self.interaction.finish(.failure(error)) }
                }
            } catch { Task { @MainActor in self.interaction.finish(.failure(error)) } }
        }
    }
}
@MainActor private final class ScanDelegate: NSObject, @preconcurrency VNDocumentCameraViewControllerDelegate {
    let interaction: DevicePresentation
    let resources: DeviceResources
    init(_ interaction: DeviceInteraction, _ resources: DeviceResources) { self.interaction = DevicePresentation(interaction); self.resources = resources }
    func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { interaction.cancel() }
    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) { interaction.finish(.failure(error)) }
    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
        do {
            guard scan.pageCount <= 10 else { throw DeviceFailure("TOO_MANY_PAGES", "Scan at most 10 pages") }
            var pages: [[String: Any]] = []
            for index in 0..<scan.pageCount {
                let url = resources.allocate(extension: "jpg")
                guard let data = scan.imageOfPage(at: index).jpegData(compressionQuality: 0.85) else { throw DeviceFailure("SCAN_FAILED", "Cannot encode page") }
                try data.write(to: url)
                pages.append(try resources.add(url))
            }
            interaction.finish(.success(["pages": pages]))
        } catch { interaction.finish(.failure(error)) }
    }
}
@MainActor private final class ContactDelegate: NSObject, @preconcurrency CNContactPickerDelegate {
    let interaction: DevicePresentation
    init(_ interaction: DeviceInteraction) { self.interaction = DevicePresentation(interaction) }
    func contactPickerDidCancel(_ picker: CNContactPickerViewController) { interaction.cancel() }
    func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        interaction.finish(.success(["name": CNContactFormatter.string(from: contact, style: .fullName) ?? "",
            "phones": contact.phoneNumbers.prefix(10).map { $0.value.stringValue },
            "emails": contact.emailAddresses.prefix(10).map { String($0.value) }]))
    }
}

extension DeviceToolsBridge {
    func resourceTool(_ name: String, _ args: [String: Any]) async throws -> [String: Any] {
        switch name {
        case "device_file_pick":
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.plainText, .pdf, .audio, UTType(filenameExtension: "md") ?? .plainText], asCopy: false)
            let delegate = DocumentDelegate(interaction, resources); picker.delegate = delegate
            interaction.retainedDelegate = delegate
            return try await interaction.present(picker) as! [String: Any]
        case "device_photo_pick":
            var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config)
            let delegate = PhotoDelegate(interaction, resources); picker.delegate = delegate
            interaction.retainedDelegate = delegate
            return try await interaction.present(picker) as! [String: Any]
        case "device_document_scan":
            guard VNDocumentCameraViewController.isSupported else { throw DeviceFailure("UNAVAILABLE", "Document scanning unavailable on this device") }
            let picker = VNDocumentCameraViewController()
            let delegate = ScanDelegate(interaction, resources); picker.delegate = delegate
            interaction.retainedDelegate = delegate
            return try await interaction.present(picker) as! [String: Any]
        case "device_contact_pick":
            let picker = CNContactPickerViewController()
            let delegate = ContactDelegate(interaction); picker.delegate = delegate
            interaction.retainedDelegate = delegate
            return try await interaction.present(picker) as! [String: Any]
        case "device_audio_record": return try await recordAudio(args)
        case "device_audio_transcribe": return try await transcribeAudio(args)
        default: break
        }
        let url = try resources.resolve(args["resource_ref"] as! String)
        if name == "device_image_recognize_text" {
            return try await Task.detached(priority: .userInitiated) {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate; request.usesLanguageCorrection = true
                try VNImageRequestHandler(url: url).perform([request])
                let observations = request.results ?? []
                var remaining = 12000
                let blocks: [[String: Any]] = observations.prefix(100).compactMap { observation in
                    guard remaining > 0, let candidate = observation.topCandidates(1).first else { return nil }
                    let text = String(candidate.string.prefix(remaining)); remaining -= text.count
                    return ["text": text, "confidence": candidate.confidence,
                            "box": [observation.boundingBox.minX, observation.boundingBox.minY, observation.boundingBox.width, observation.boundingBox.height]]
                }
                return ["blocks": blocks, "truncated": observations.count > 100 || remaining == 0,
                        "coordinate_system": "normalized_bottom_left", "processing": "on_device"]
            }.value
        }
        if name == "device_file_read_text" {
            let offset = (args["offset"] as? NSNumber)?.intValue ?? 0
            let pageNumber = (args["page"] as? NSNumber)?.intValue ?? 1
            return try await Task.detached(priority: .userInitiated) {
                let text: String
                var pageCount = 0
                if url.pathExtension.lowercased() == "pdf" {
                    guard let pdf = PDFDocument(url: url), !pdf.isLocked, pageNumber <= pdf.pageCount,
                          let page = pdf.page(at: pageNumber - 1) else { throw DeviceFailure("PDF_UNAVAILABLE", "Invalid page or locked PDF") }
                    pageCount = pdf.pageCount; text = page.string ?? ""
                } else {
                    guard ["txt", "md", "text"].contains(url.pathExtension.lowercased()) else { throw DeviceFailure("UNSUPPORTED_FORMAT", "Select UTF-8 text, Markdown or PDF") }
                    text = try String(contentsOf: url, encoding: .utf8)
                }
                guard offset <= text.count else { throw DeviceFailure("INVALID_OFFSET", "Offset beyond text length") }
                let chunk = String(text.dropFirst(offset).prefix(12000))
                return ["text": chunk, "offset": offset, "next_offset": offset + chunk.count < text.count ? offset + chunk.count : NSNull(),
                        "page": pageNumber, "page_count": pageCount, "empty_text_hint": "Scanned pages may require OCR"]
            }.value
        }
        throw DeviceFailure("TOOL_UNAVAILABLE", "Unknown resource tool")
    }

    private func recordAudio(_ args: [String: Any]) async throws -> [String: Any] {
        let granted = await withCheckedContinuation { continuation in AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) } }
        guard granted else { throw DeviceFailure("PERMISSION_DENIED", "Allow microphone access in Settings") }
        let seconds = (args["seconds"] as! NSNumber).intValue
        try await interaction.confirm("开始录音", "将在前台录音 \(seconds) 秒。切到后台或停止任务将结束录音。")
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default); try session.setActive(true)
        defer { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
        let url = resources.allocate(extension: "m4a")
        let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue])
        guard recorder.record(forDuration: Double(seconds)) else { throw DeviceFailure("RECORDING_FAILED", "Cannot start recording") }
        var retained = false
        defer { recorder.stop(); if !retained { try? FileManager.default.removeItem(at: url) } }
        for _ in 0..<(seconds * 4) {
            try await Task.sleep(nanoseconds: 250_000_000)
            try checkForeground()
        }
        recorder.stop()
        let resource = try resources.add(url)
        retained = true
        return resource
    }
    private func transcribeAudio(_ args: [String: Any]) async throws -> [String: Any] {
        let url = try resources.resolve(args["resource_ref"] as! String)
        let duration = try await AVURLAsset(url: url).load(.duration)
        guard duration.seconds.isFinite, duration.seconds > 0, duration.seconds <= 60 else { throw DeviceFailure("AUDIO_TOO_LONG", "Choose at most 60 seconds of audio") }
        let authorization = await withCheckedContinuation { continuation in SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) } }
        guard authorization == .authorized else { throw DeviceFailure("PERMISSION_DENIED", "Allow speech recognition in Settings") }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: args["language"] as! String)),
              recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else { throw DeviceFailure("ON_DEVICE_UNAVAILABLE", "Local recognition unavailable for this language/device; audio was not uploaded") }
        try checkForeground()
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.requiresOnDeviceRecognition = true; request.shouldReportPartialResults = false
        let operation = DeviceSpeechOperation()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                operation.start(recognizer, request, continuation)
            }
        }, onCancel: { Task { @MainActor in operation.cancel() } })
    }
}

@MainActor private final class DeviceSpeechOperation {
    private var continuation: CheckedContinuation<[String: Any], Error>?
    private var task: SFSpeechRecognitionTask?
    private var cancelled = false
    func start(_ recognizer: SFSpeechRecognizer, _ request: SFSpeechURLRecognitionRequest,
               _ continuation: CheckedContinuation<[String: Any], Error>) {
        self.continuation = continuation
        if cancelled { finish(.failure(CancellationError())); return }
        task = recognizer.recognitionTask(with: request) { result, error in
            Task { @MainActor in
                if let error { self.finish(.failure(error)) }
                else if let result, result.isFinal {
                    let text = result.bestTranscription.formattedString
                    self.finish(.success(["text": String(text.prefix(12000)), "truncated": text.count > 12000, "processing": "on_device"]))
                }
            }
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 65_000_000_000)
            finish(.failure(DeviceFailure("TRANSCRIPTION_TIMEOUT", "Local transcription timed out")))
        }
    }
    func cancel() { cancelled = true; finish(.failure(CancellationError())) }
    private func finish(_ result: Result<[String: Any], Error>) {
        guard let continuation else { return }
        self.continuation = nil
        task?.cancel(); task = nil
        continuation.resume(with: result)
    }
}
