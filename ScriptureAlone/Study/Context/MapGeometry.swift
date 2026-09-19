import SwiftUI
import ScriptureAloneCore

/// Equirectangular projection tuned for the Levant: longitude is scaled by cos 32°, so shapes
/// look right around Jerusalem without the distortion of Web Mercator at this small a scale.
/// Map units are degrees of latitude; y grows southward like the screen.
enum MapProjection {
    static let lonScale = cos(32.0 * .pi / 180)
    /// The whole map: 20°W–60°E, 10°N–45°N.
    static let bounds = rect(minLon: -20, minLat: 10, maxLon: 60, maxLat: 45)

    static func point(lon: Double, lat: Double) -> CGPoint {
        CGPoint(x: lon * lonScale, y: -lat)
    }

    static func rect(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) -> CGRect {
        let a = point(lon: minLon, lat: maxLat)
        let b = point(lon: maxLon, lat: minLat)
        return CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y)
    }

    /// Smallest rect containing the points, grown to at least `minSpan` degrees each way.
    static func fit(_ points: [CGPoint], minSpan: CGFloat = 2.4) -> CGRect? {
        guard let first = points.first else { return nil }
        var rect = CGRect(origin: first, size: .zero)
        for point in points.dropFirst() { rect = rect.union(CGRect(origin: point, size: .zero)) }
        let width = max(rect.width, minSpan * lonScale)
        let height = max(rect.height, minSpan)
        return CGRect(x: rect.midX - width / 2, y: rect.midY - height / 2, width: width, height: height)
    }
}

/// Where the map is looking: the center (map units) and points per map unit.
struct MapCamera: Equatable {
    var center: CGPoint
    var scale: CGFloat

    static let maximumScale: CGFloat = 2400

    static func minimumScale(for size: CGSize) -> CGFloat {
        let bounds = MapProjection.bounds
        guard size.width > 0, size.height > 0 else { return 4 }
        return max(size.width / bounds.width, size.height / bounds.height)
    }

    static func fitting(_ rect: CGRect, in size: CGSize, padding: CGFloat = 36) -> MapCamera {
        let width = max(size.width - padding * 2, 40)
        let height = max(size.height - padding * 2, 40)
        let scale = min(width / max(rect.width, 0.01), height / max(rect.height, 0.01))
        var camera = MapCamera(center: CGPoint(x: rect.midX, y: rect.midY), scale: scale)
        camera.clamp(to: size)
        return camera
    }

    func toScreen(_ point: CGPoint, in size: CGSize) -> CGPoint {
        CGPoint(x: (point.x - center.x) * scale + size.width / 2, y: (point.y - center.y) * scale + size.height / 2)
    }

    func toMap(_ point: CGPoint, in size: CGSize) -> CGPoint {
        CGPoint(x: (point.x - size.width / 2) / scale + center.x, y: (point.y - size.height / 2) / scale + center.y)
    }

    func transform(in size: CGSize) -> CGAffineTransform {
        CGAffineTransform(translationX: size.width / 2, y: size.height / 2)
            .scaledBy(x: scale, y: scale)
            .translatedBy(x: -center.x, y: -center.y)
    }

    /// The map-unit rect visible on screen.
    func visibleRect(in size: CGSize) -> CGRect {
        let origin = toMap(.zero, in: size)
        return CGRect(x: origin.x, y: origin.y, width: size.width / scale, height: size.height / scale)
    }

    /// Keeps the map filling the view and the scale in range.
    mutating func clamp(to size: CGSize) {
        scale = min(max(scale, Self.minimumScale(for: size)), Self.maximumScale)
        let bounds = MapProjection.bounds
        let halfWidth = size.width / 2 / scale
        let halfHeight = size.height / 2 / scale
        center.x = bounds.width <= halfWidth * 2 ? bounds.midX : min(max(center.x, bounds.minX + halfWidth), bounds.maxX - halfWidth)
        center.y = bounds.height <= halfHeight * 2 ? bounds.midY : min(max(center.y, bounds.minY + halfHeight), bounds.maxY - halfHeight)
    }

    /// Zooms by `factor`, keeping the map point under `anchor` (screen) fixed.
    mutating func zoom(by factor: CGFloat, anchor: CGPoint, in size: CGSize) {
        let before = toMap(anchor, in: size)
        scale *= factor
        scale = min(max(scale, Self.minimumScale(for: size)), Self.maximumScale)
        let after = toMap(anchor, in: size)
        center.x += before.x - after.x
        center.y += before.y - after.y
        clamp(to: size)
    }
}

/// Base-map geometry as SwiftUI paths in map units, built once.
struct BasemapPaths {
    let landCoarse: Path
    let landFine: Path
    let lakesCoarse: Path
    let lakesFine: Path
    let riversCoarse: Path
    /// Major rivers (Natural Earth rank ≤ 6) and the rest, so minor ones appear only up close.
    let riversMajorFine: Path
    let riversMinorFine: Path

    init(_ map: Basemap) {
        func polygons(_ kind: Basemap.Kind, _ detail: Basemap.Detail) -> Path {
            var path = Path()
            for ring in map.layer(kind, detail)?.rings ?? [] {
                path.addLines(ring.points.map { MapProjection.point(lon: $0.x, lat: $0.y) })
                path.closeSubpath()
            }
            return path
        }
        func lines(_ detail: Basemap.Detail, ranks: ClosedRange<Int>) -> Path {
            var path = Path()
            for ring in map.layer(.river, detail)?.rings ?? [] where ranks.contains(ring.rank) {
                path.addLines(ring.points.map { MapProjection.point(lon: $0.x, lat: $0.y) })
            }
            return path
        }
        landCoarse = polygons(.land, .coarse)
        landFine = polygons(.land, .fine)
        lakesCoarse = polygons(.lake, .coarse)
        lakesFine = polygons(.lake, .fine)
        riversCoarse = lines(.coarse, ranks: 0...7)
        riversMajorFine = lines(.fine, ranks: 0...6)
        riversMinorFine = lines(.fine, ranks: 7...255)
    }
}

/// Map colors, tuned for legibility in light and dark appearance.
struct MapPalette {
    let sea: Color
    let land: Color
    let coast: Color
    let water: Color
    let river: Color
    let graticule: Color
    let ink: Color
    let secondaryInk: Color
    let waterInk: Color
    let halo: Color
    let pin: Color
    let pinOutline: Color
    let background: Color

    static func forScheme(_ scheme: ColorScheme) -> MapPalette {
        scheme == .dark
            ? MapPalette(sea: Color(contextHex: "#101C26"), land: Color(contextHex: "#2A2B2A"),
                         coast: Color(contextHex: "#4D6475"), water: Color(contextHex: "#15283A"),
                         river: Color(contextHex: "#4F86AD"), graticule: Color.white.opacity(0.05),
                         ink: Color(contextHex: "#ECE7DD"), secondaryInk: Color(contextHex: "#A8A193"),
                         waterInk: Color(contextHex: "#7FAED0"), halo: Color(contextHex: "#1A1B1A").opacity(0.9),
                         pin: Color(contextHex: "#FF7A5C"), pinOutline: Color(contextHex: "#1A1B1A"),
                         background: Color(contextHex: "#6E6A61"))
            : MapPalette(sea: Color(contextHex: "#CFE2EC"), land: Color(contextHex: "#F4EFE3"),
                         coast: Color(contextHex: "#8FAEC0"), water: Color(contextHex: "#BCD7E6"),
                         river: Color(contextHex: "#6C9FC2"), graticule: Color.black.opacity(0.05),
                         ink: Color(contextHex: "#2B2620"), secondaryInk: Color(contextHex: "#7A6F60"),
                         waterInk: Color(contextHex: "#3F6F92"), halo: Color(contextHex: "#F4EFE3").opacity(0.95),
                         pin: Color(contextHex: "#C0392B"), pinOutline: .white,
                         background: Color(contextHex: "#8A8172"))
    }
}
