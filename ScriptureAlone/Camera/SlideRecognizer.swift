import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
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

/// Finds the screen a slide was shown on — a projector screen, a TV, a monitor — in a photo of
/// the room, and straightens it out to fill the picture, so the recognizer reads the slide and not
/// the room around it. On device, like the recognizer.
nonisolated enum SlideScreen {
    /// The screen, straightened; nil when no screen is found, or when it already fills the photo
    /// and there's nothing to cut away.
    @concurrent static func straightened(_ image: CGImage) async -> CGImage? {
        var request = DetectRectanglesRequest()
        // Slides are 16:9 or 4:3; seen from the side of the room, narrower.
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1
        request.minimumSize = 0.25
        request.minimumConfidence = 0.5
        request.maximumObservations = 8
        guard let observations = try? await request.perform(on: image) else { return nil }

        // Vision's points and Core Image's both start at the lower left.
        let size = CGSize(width: image.width, height: image.height)
        func pixel(_ point: NormalizedPoint) -> CGPoint {
            CGPoint(x: point.cgPoint.x * size.width, y: point.cgPoint.y * size.height)
        }
        // The biggest rectangle is the screen: boxes and pictures on the slide sit inside it.
        let quads = observations.map { [$0.topLeft, $0.topRight, $0.bottomRight, $0.bottomLeft].map(pixel) }
        guard let corners = quads.max(by: { area($0) < area($1) }) else { return nil }
        let share = area(corners) / (size.width * size.height)
        guard share > 0.1, share < 0.9 else { return nil }

        let filter = CIFilter.perspectiveCorrection()
        filter.inputImage = CIImage(cgImage: image)
        filter.topLeft = corners[0]
        filter.topRight = corners[1]
        filter.bottomRight = corners[2]
        filter.bottomLeft = corners[3]
        guard let output = filter.outputImage else { return nil }
        return CIContext().createCGImage(output, from: output.extent.integral)
    }

    /// A quadrilateral's area, by the shoelace formula.
    private static func area(_ corners: [CGPoint]) -> Double {
        var sum = 0.0
        for index in corners.indices {
            let a = corners[index], b = corners[(index + 1) % corners.count]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
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
