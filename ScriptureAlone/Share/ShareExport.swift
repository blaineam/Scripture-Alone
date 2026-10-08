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
    nonisolated static let scale: CGFloat = 2

    /// Draws the ground at the export size first (`prepare` does it off the main thread), so the card
    /// lays its text over a backdrop with one image pixel per output pixel.
    static func render(_ content: ShareCardContent, style: ShareStyle) -> (image: ShareImage, cgImage: CGImage)? {
        let (backdrop, colors) = prepare(style.background, size: style.aspect.size, ink: style.ink, shadow: style.shadow)
        let renderer = ImageRenderer(content: ShareCard(content: content, style: style, colors: colors, backdrop: backdrop))
        renderer.scale = scale
        renderer.isOpaque = true
        guard let cgImage = renderer.cgImage, let png = pngData(cgImage) else { return nil }
        return (ShareImage(png: png, filename: filename(for: content)), cgImage)
    }

    /// The ground at export size and the colors that read on it; slow the first time, then cached.
    nonisolated static func prepare(_ background: ShareBackground, size: CGSize, ink: UInt32?,
                                    shadow: ShareShadow) -> (backdrop: CGImage?, colors: ShareColors) {
        let backdrop = ShareBackdrop.image(background, size: size, pixelsPerPoint: scale)
        return (backdrop, ShareContrast.resolve(background: background, ink: ink, shadow: shadow,
                                                stats: ShareBackdrop.stats(background)))
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
            String(localized: "Scripture Alone can’t add to your photo library. Allow it in Settings › Privacy & Security › Photos.", comment: "“Settings › Privacy & Security › Photos” should match the system Settings app's menu names.")
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
