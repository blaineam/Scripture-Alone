import CoreGraphics
import Foundation
import ImageIO
import Vision
import ScriptureAloneCore

/// On-device text recognition for a photographed slide. Nothing leaves the device: Vision runs
/// locally and the image is never written anywhere by this type.
nonisolated enum SlideRecognizer {
    /// Book names and abbreviations, so language correction doesn't "fix" "Eph" into "Ech".
    private static let customWords: [String] = {
        var words = Set<String>()
        for book in BookID.allCases {
            words.insert(book.name)
            if book.abbreviation.count > 2 { words.insert(book.abbreviation) }
        }
        return words.sorted()
    }()

    /// Recognizes every line of text on the image, with geometry normalized to the image
    /// (origin top-left) so `SlideParser` can tell the title from the fine print.
    @concurrent static func lines(in image: CGImage, orientation: CGImagePropertyOrientation = .up) async throws -> [SlideLine] {
        var request = RecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        request.customWords = customWords
        let observations = try await request.perform(on: image, orientation: orientation)

        // Line height in pixels comes from the quadrilateral, so a slide shot at an angle still
        // measures its text by the letters' own height, not by the tilted bounding box.
        let rotated = [.left, .right, .leftMirrored, .rightMirrored].contains(orientation)
        let size = rotated
            ? CGSize(width: image.height, height: image.width)
            : CGSize(width: image.width, height: image.height)
        return observations.compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            func pixel(_ point: NormalizedPoint) -> CGPoint {
                CGPoint(x: point.cgPoint.x * size.width, y: point.cgPoint.y * size.height)
            }
            func distance(_ a: CGPoint, _ b: CGPoint) -> Double { hypot(a.x - b.x, a.y - b.y) }
            let letterHeight = (distance(pixel(observation.topLeft), pixel(observation.bottomLeft))
                + distance(pixel(observation.topRight), pixel(observation.bottomRight))) / 2
            let box = observation.boundingBox.cgRect
            return SlideLine(candidate.string,
                             x: box.minX,
                             y: 1 - box.maxY,
                             width: box.width,
                             height: letterHeight / max(size.height, 1),
                             confidence: Double(candidate.confidence))
        }
    }

    /// Recognizes and reads the slide in one step.
    @concurrent static func read(_ image: CGImage, orientation: CGImagePropertyOrientation = .up) async throws -> (lines: [SlideLine], reading: SlideReading) {
        let lines = try await lines(in: image, orientation: orientation)
        return (lines, SlideParser.read(lines))
    }
}

/// Decoding and shrinking photos without UIKit or AppKit, so the same code runs everywhere.
nonisolated enum SlideImage {
    /// Decodes image data (HEIC, JPEG, PNG…), applying its EXIF orientation and downscaling
    /// to at most `maxPixelSize` on the long edge — plenty for slide text, and quick to read.
    static func decode(_ data: Data, maxPixelSize: Int = 3000) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    static func decode(contentsOf url: URL, maxPixelSize: Int = 3000) -> CGImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return decode(data, maxPixelSize: maxPixelSize)
    }

    /// A modest JPEG for keeping the photo with a note (only when the user asks to).
    static func jpeg(_ image: CGImage, maxPixelSize: Int = 1600, quality: Double = 0.7) -> Data? {
        let data = NSMutableData()
        guard let small = scaled(image, maxPixelSize: maxPixelSize),
              let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, small, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    static func scaled(_ image: CGImage, maxPixelSize: Int) -> CGImage? {
        let longEdge = max(image.width, image.height)
        guard longEdge > maxPixelSize else { return image }
        let scale = Double(maxPixelSize) / Double(longEdge)
        let width = max(1, Int(Double(image.width) * scale)), height = max(1, Int(Double(image.height) * scale))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }
}
