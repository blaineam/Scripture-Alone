import Foundation
import ScriptureAloneCore

/// Downloads one translation from eBible.org to a temporary file.
///
/// The app makes no network request on its own: this runs only when someone taps a translation in
/// the catalogue. Nothing about the device is sent — it is a plain GET for a public file.
struct CatalogDownloader: Sendable {
    enum Failure: LocalizedError {
        case http(Int)
        case empty

        var errorDescription: String? {
            switch self {
            case .http(let code): "eBible.org returned HTTP \(code) for that translation."
            case .empty: "That download arrived empty."
            }
        }
    }

    /// Streams the zip to a temp file, reporting 0...1 where the server states a length.
    /// The caller deletes the file; the importer copies what it needs.
    func download(_ translation: CatalogTranslation,
                  session: URLSession = .shared,
                  progress: @Sendable @escaping (Double?) -> Void) async throws -> URL {
        var request = URLRequest(url: translation.downloadURL)
        request.timeoutInterval = 60
        let (bytes, response) = try await session.bytes(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw Failure.http(http.statusCode)
        }
        let expected = response.expectedContentLength
        let destination = FileManager.default.temporaryDirectory
            .appending(path: "\(translation.id)-\(UUID().uuidString).zip")
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }

        var buffer = Data()
        buffer.reserveCapacity(64 * 1024)
        var written: Int64 = 0
        var lastReported = Date.distantPast

        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 64 * 1024 {
                try handle.write(contentsOf: buffer)
                written += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                // Reporting every chunk floods the main actor; a few times a second is plenty.
                if Date().timeIntervalSince(lastReported) > 0.1 {
                    lastReported = Date()
                    progress(expected > 0 ? min(1, Double(written) / Double(expected)) : nil)
                }
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            written += Int64(buffer.count)
        }
        guard written > 0 else {
            try? FileManager.default.removeItem(at: destination)
            throw Failure.empty
        }
        progress(1)
        return destination
    }
}
