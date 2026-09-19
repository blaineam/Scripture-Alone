import SwiftUI
import ImageIO
import UniformTypeIdentifiers
#if os(iOS)
import Photos
#endif

/// A rendered card, handed to the share sheet or the save panel as a PNG.
nonisolated struct ShareImage: Transferable, Sendable {
    let png: Data
    let filename: String

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { $0.png }
            .suggestedFileName { $0.filename }
    }
}

enum ShareRenderer {
    /// Export scale: cards are laid out 1080 pt on the long side, so this makes 2160 px.
    static let scale: CGFloat = 2

    static func render(_ content: ShareCardContent, style: ShareStyle) -> (image: ShareImage, cgImage: CGImage)? {
        let renderer = ImageRenderer(content: ShareCard(content: content, style: style))
        renderer.scale = scale
        renderer.isOpaque = true
        guard let cgImage = renderer.cgImage, let png = pngData(cgImage) else { return nil }
        return (ShareImage(png: png, filename: filename(for: content)), cgImage)
    }

    static func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// "John 3.16-17 ASV.png" — colons and dashes that file systems dislike are swapped out.
    static func filename(for content: ShareCardContent) -> String {
        let reference = content.reference
            .replacingOccurrences(of: ":", with: ".")
            .replacingOccurrences(of: "–", with: "-")
            .replacingOccurrences(of: "/", with: "-")
        return "\(reference) \(content.translation).png"
    }
}

enum SharePasteboard {
    static func copy(_ url: URL) {
        #if os(iOS)
        UIPasteboard.general.url = url
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url.absoluteString, forType: .string)
        #endif
    }
}

#if os(iOS)
enum PhotoSaver {
    enum Failure: LocalizedError {
        case denied
        var errorDescription: String? {
            "Scripture Alone can’t add to your photo library. Allow it in Settings › Privacy & Security › Photos."
        }
    }

    /// Adds the PNG as-is (no re-encoding) with add-only access.
    static func save(_ png: Data) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw Failure.denied }
        try await PHPhotoLibrary.shared().performChanges(changes(adding: png))
    }

    /// PhotoKit runs the change block on its own queue, so it must not inherit main-actor isolation.
    nonisolated private static func changes(adding png: Data) -> @Sendable () -> Void {
        {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: png, options: nil)
        }
    }
}
#endif
