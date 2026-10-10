// Host-only tests execute the production transfer/cache and GGUF checksum code.
private final class DownloadResult: @unchecked Sendable { var error: String? }
private final class LocalMLX: @unchecked Sendable {
    static let shared = LocalMLX()
    func run(download: Bool = false, _ operation: @escaping @Sendable () async throws -> Void) -> UnsafeMutablePointer<CChar>? {
        let done = DispatchSemaphore(value: 0), result = DownloadResult()
        Task.detached {
            do { try await operation() } catch { result.error = String(describing: error) }
            done.signal()
        }
        done.wait()
        return result.error.flatMap { strdup($0) }
    }
}

let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let destination = directory.appendingPathComponent("weights.gguf")
let content = Data("verified weights".utf8)
let hash = SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()
let cached = directory.appendingPathComponent(".model-" + hash + ".resume").appendingPathExtension("complete")
try content.write(to: cached)
let reply = "https://huggingface.co/test/model/resolve/pinned/weights.gguf".withCString { url in
    destination.path.withCString { path in hash.withCString { digest in ocModelDownload(url, path, digest, Int64(content.count)) } }
}
precondition(reply == nil)
let installed = try Data(contentsOf: destination)
precondition(installed == content)
precondition(!FileManager.default.fileExists(atPath: cached.path))
try FileManager.default.removeItem(at: destination)
try Data(repeating: 0, count: content.count).write(to: cached)
let invalid = "https://huggingface.co/test/model/resolve/pinned/weights.gguf".withCString { url in
    destination.path.withCString { path in hash.withCString { digest in ocModelDownload(url, path, digest, Int64(content.count)) } }
}
precondition(invalid != nil)
if let invalid { free(invalid) }
precondition(!FileManager.default.fileExists(atPath: destination.path))
precondition(!FileManager.default.fileExists(atPath: cached.path))
private let operation = ResumableModelDownload(resumeFile: directory.appendingPathComponent("cancelled.resume"))
operation.cancel()
var cancellationReported = false
// Cancel before start must complete without making a network request.
private let cancellation = DownloadResult()
operation.start(url: URL(string: "https://invalid.example/weights")!) { result in
    if case .failure(let error) = result { cancellation.error = error is CancellationError ? "cancelled" : "unexpected" }
}
cancellationReported = cancellation.error == "cancelled"
precondition(cancellationReported)
print("iOS download: verified cache install, corrupt cache rejection, pre-start cancellation passed")
