import SwiftUI
import ScriptureAloneCore

/// A place drawn prominently on the map (mentioned in the chapter, or a journey stop).
struct MapPin: Identifiable, Hashable {
    let id: Int
    let name: String
    let point: CGPoint
    let kind: PlaceKind
    let isArea: Bool
    var tint: Color?

    init(place: Place, tint: Color? = nil) {
        id = place.id
        name = place.name
        point = MapProjection.point(lon: place.longitude, lat: place.latitude)
        kind = place.kind
        isArea = place.isArea
        self.tint = tint
    }

    init(id: Int, name: String, lon: Double, lat: Double, kind: PlaceKind, isArea: Bool, tint: Color? = nil) {
        self.id = id
        self.name = name
        point = MapProjection.point(lon: lon, lat: lat)
        self.kind = kind
        self.isArea = isArea
        self.tint = tint
    }
}

struct MapRoute: Identifiable, Hashable {
    let id: String
    let name: String
    let points: [CGPoint]
    let color: Color
}

/// A large area label, e.g. a tribe's allotment.
struct MapTag: Hashable {
    let text: String
    let point: CGPoint
}

struct MapContent {
    var pins: [MapPin] = []
    var routes: [MapRoute] = []
    var tags: [MapTag] = []
    /// Draw the most-mentioned places faintly for orientation.
    var showsBackgroundPlaces = true
    var selectedID: Int?
    /// The region to show first (map units); the whole map when nil.
    var fitRect: CGRect?

    static func fitRect(for pins: [MapPin], extra: [CGPoint] = []) -> CGRect? {
        MapProjection.fit(pins.map(\.point) + extra)
    }
}

/// The offline Bible map: Natural Earth land, lakes and rivers drawn with Canvas, places and
/// routes on top, priority-based labels that never collide. Drag to pan, pinch to zoom.
struct BibleMapView: View {
    let content: MapContent
    /// Change to re-frame the map on `content.fitRect`.
    var fitToken: AnyHashable = 0
    var showsControls = true
    /// False for a map shown as a picture in a scrolling page: no panning or zooming to catch
    /// the reader's scroll, no controls. Pins still answer a tap.
    var interactive = true
    var onSelect: ((Int) -> Void)?

    @Environment(\.colorScheme) private var colorScheme
    @State private var camera: MapCamera?
    @State private var size: CGSize = .zero
    @State private var dragOrigin: MapCamera?
    @State private var zoomOrigin: MapCamera?
    /// Until the user pans or zooms, resizing re-frames the content instead of keeping the scale.
    @State private var userMoved = false

    private var library: ContextLibrary { .shared }

    var body: some View {
        let palette = MapPalette.forScheme(colorScheme)
        // Read state here, in body, so SwiftUI redraws the Canvas when it changes.
        let camera = camera
        let content = content
        let library = library
        Canvas(opaque: true) { context, canvasSize in
            guard let camera else {
                context.fill(Path(CGRect(origin: .zero, size: canvasSize)), with: .color(palette.sea))
                return
            }
            MapRenderer(content: content, camera: camera, size: canvasSize, palette: palette, library: library)
                .draw(in: &context)
        }
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear { resize(proxy.size) }
                    .onChange(of: proxy.size) { _, newSize in resize(newSize) }
            }
        }
        .onChange(of: fitToken) { fit(animated: true) }
        .gesture(pan.simultaneously(with: zoom), including: interactive ? .all : .subviews)
        .simultaneousGesture(SpatialTapGesture().onEnded { value in
            if let id = hitTest(value.location) { onSelect?(id) }
        })
        .accessibilityRepresentation {
            VStack(alignment: .leading) {
                Text(accessibilitySummary)
                ForEach(content.pins) { pin in
                    Button(pin.name) { onSelect?(pin.id) }
                        .accessibilityHint("Shows the verses that mention \(pin.name).")
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if showsControls && interactive { controls.padding(10) }
        }
        .clipped()
    }

    private var accessibilitySummary: String {
        if content.pins.isEmpty { return String(localized: "Map of the lands of the Bible.", comment: "Accessibility description of the map when no places are pinned") }
        let names = content.pins.prefix(12).map(\.name).joined(separator: ", ")
        let more = content.pins.count > 12
            ? String(localized: "\(content.pins.count - 12) more", comment: "Accessibility: ends a list of place names on a map. %lld is how many more places are pinned.")
            : ""
        let list = more.isEmpty ? names : String(localized: "\(names), and \(more)", comment: "Accessibility: joins a comma-separated list of place names with “%lld more”.")
        return String(localized: "Map showing \(list).", comment: "Accessibility description of the map. %@ is a list of place names.")
    }

    // MARK: Controls

    private var controls: some View {
        VStack(spacing: 8) {
            Button { zoomStep(1.8) } label: { Image(systemName: "plus").frame(width: 18, height: 18) }
                .accessibilityLabel("Zoom In")
            Button { zoomStep(1 / 1.8) } label: { Image(systemName: "minus").frame(width: 18, height: 18) }
                .accessibilityLabel("Zoom Out")
            Button { fit(animated: true) } label: {
                Image(systemName: "arrow.up.left.and.down.right.magnifyingglass").frame(width: 18, height: 18)
            }
            .accessibilityLabel("Show All Places")
        }
        .font(.body.weight(.semibold))
        .buttonStyle(.glass)
        .controlSize(.small)
    }

    private func resize(_ newSize: CGSize) {
        size = newSize
        if camera == nil || !userMoved { fit() } else { camera?.clamp(to: newSize) }
    }

    private func zoomStep(_ factor: CGFloat) {
        guard var next = camera else { return }
        userMoved = true
        next.zoom(by: factor, anchor: CGPoint(x: size.width / 2, y: size.height / 2), in: size)
        withAnimation(.snappy) { camera = next }
    }

    private func fit(animated: Bool = false) {
        guard size.width > 0, size.height > 0 else { return }
        userMoved = false
        let target = MapCamera.fitting(content.fitRect ?? MapProjection.bounds, in: size)
        if animated { withAnimation(.snappy) { camera = target } } else { camera = target }
    }

    // MARK: Gestures

    private var pan: some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                guard let current = camera else { return }
                let origin = dragOrigin ?? current
                if dragOrigin == nil { dragOrigin = current }
                userMoved = true
                var next = current
                next.center = CGPoint(x: origin.center.x - value.translation.width / current.scale,
                                      y: origin.center.y - value.translation.height / current.scale)
                next.clamp(to: size)
                camera = next
            }
            .onEnded { _ in dragOrigin = nil }
    }

    private var zoom: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                guard let current = camera else { return }
                let origin = zoomOrigin ?? current
                if zoomOrigin == nil { zoomOrigin = current }
                userMoved = true
                var next = origin
                next.zoom(by: value.magnification, anchor: value.startLocation, in: size)
                camera = next
                dragOrigin = nil
            }
            .onEnded { _ in zoomOrigin = nil }
    }

    private func hitTest(_ location: CGPoint) -> Int? {
        guard let camera else { return nil }
        let markers = MapRenderer(content: content, camera: camera, size: size,
                                  palette: .forScheme(colorScheme), library: library).markers()
        let hit = markers
            .map { ($0, hypot($0.screen.x - location.x, $0.screen.y - location.y)) }
            .filter { $0.1 < ($0.0.emphasized ? 26 : 16) }
            .min { ($0.0.emphasized ? 0 : 1, $0.1) < ($1.0.emphasized ? 0 : 1, $1.1) }
        return hit?.0.id
    }
}

/// Draws one frame of the map. Pure function of its inputs, so hit-testing can reuse it.
private struct MapRenderer {
    let content: MapContent
    let camera: MapCamera
    let size: CGSize
    let palette: MapPalette
    let library: ContextLibrary

    struct Marker {
        let id: Int
        let name: String
        let screen: CGPoint
        let emphasized: Bool
        let kind: PlaceKind
        let isArea: Bool
        let tint: Color?
        let priority: Int
    }

    /// Places drawn at this zoom, emphasized first, then background places by prominence.
    func markers() -> [Marker] {
        let visible = camera.visibleRect(in: size).insetBy(dx: -40 / camera.scale, dy: -40 / camera.scale)
        var result: [Marker] = []
        var seen = Set<Int>()
        var emphasizedPoints: [CGPoint] = []
        for (index, pin) in content.pins.enumerated() where !seen.contains(pin.id) {
            seen.insert(pin.id)
            let screen = camera.toScreen(pin.point, in: size)
            // Two names for one site (Jerusalem and Zion) share a marker.
            if emphasizedPoints.contains(where: { hypot($0.x - screen.x, $0.y - screen.y) < 3 }) { continue }
            emphasizedPoints.append(screen)
            result.append(Marker(id: pin.id, name: pin.name, screen: screen, emphasized: true, kind: pin.kind,
                                 isArea: pin.isArea, tint: pin.tint, priority: index))
        }
        guard content.showsBackgroundPlaces else { return result }
        let threshold: Int = switch camera.scale {
        case ..<12: 150
        case ..<30: 50
        case ..<70: 16
        case ..<160: 6
        case ..<400: 2
        default: 1
        }
        var count = 0
        for place in library.prominentPlaces where place.mentions >= threshold && !seen.contains(place.id) {
            let point = MapProjection.point(lon: place.longitude, lat: place.latitude)
            guard visible.contains(point) else { continue }
            let screen = camera.toScreen(point, in: size)
            if emphasizedPoints.contains(where: { hypot($0.x - screen.x, $0.y - screen.y) < 3 }) { continue }
            seen.insert(place.id)
            result.append(Marker(id: place.id, name: place.name, screen: screen, emphasized: false, kind: place.kind,
                                 isArea: place.isArea, tint: nil, priority: 1000 + count))
            count += 1
            if count >= 220 { break }
        }
        return result
    }

    func draw(in context: inout GraphicsContext) {
        let bounds = CGRect(origin: .zero, size: size)
        context.fill(Path(bounds), with: .color(palette.sea))
        drawBasemap(in: context)
        drawRoutes(in: context)

        let markers = markers()
        var occupied: [CGRect] = []
        // Background dots first so emphasized pins sit on top.
        for marker in markers where !marker.emphasized && !marker.isArea {
            let dot = CGRect(x: marker.screen.x - 2.2, y: marker.screen.y - 2.2, width: 4.4, height: 4.4)
            context.fill(Path(ellipseIn: dot), with: .color(marker.kind == .water ? palette.river : palette.background))
        }
        for marker in markers where marker.emphasized {
            if let rect = drawPin(marker, in: context) { occupied.append(rect) }
        }

        // Labels, most important first; anything that would collide is skipped. A chapter's own
        // place names may, as a last resort, overlap a dot — but never each other.
        var labels: [(GraphicsContext.ResolvedText, CGRect)] = []
        var strongLabels: [CGRect] = []
        func place(_ text: Text, near point: CGPoint, gap: CGFloat, centered: Bool, strong: Bool = false) {
            let resolved = context.resolve(text)
            let measured = resolved.measure(in: CGSize(width: 320, height: 80))
            let w = measured.width, h = measured.height
            let diagonal = gap * 0.7
            let candidates: [CGPoint] = centered
                ? [CGPoint(x: point.x - w / 2, y: point.y - h / 2),
                   CGPoint(x: point.x - w / 2, y: point.y + 14),
                   CGPoint(x: point.x - w / 2, y: point.y - 14 - h),
                   CGPoint(x: point.x - w * 0.15, y: point.y - h / 2),
                   CGPoint(x: point.x - w * 0.85, y: point.y - h / 2)]
                : [CGPoint(x: point.x + gap, y: point.y - h / 2),
                   CGPoint(x: point.x - gap - w, y: point.y - h / 2),
                   CGPoint(x: point.x - w / 2, y: point.y - gap - h),
                   CGPoint(x: point.x - w / 2, y: point.y + gap),
                   CGPoint(x: point.x + diagonal, y: point.y - diagonal - h),
                   CGPoint(x: point.x + diagonal, y: point.y + diagonal),
                   CGPoint(x: point.x - diagonal - w, y: point.y - diagonal - h),
                   CGPoint(x: point.x - diagonal - w, y: point.y + diagonal)]
            let area = bounds.insetBy(dx: -w / 3, dy: -4)
            for lenient in strong ? [false, true] : [false] {
                for origin in candidates {
                    let rect = CGRect(origin: origin, size: measured)
                    let padded = rect.insetBy(dx: -2, dy: -1)
                    let blockers = lenient ? strongLabels : occupied
                    guard area.contains(rect), !blockers.contains(where: { $0.intersects(padded) }) else { continue }
                    occupied.append(padded)
                    if strong { strongLabels.append(padded) }
                    labels.append((resolved, rect))
                    return
                }
            }
        }

        for marker in markers where marker.emphasized {
            let selected = marker.id == content.selectedID
            place(label(marker, selected: selected), near: marker.screen, gap: marker.isArea ? 0 : 8,
                  centered: marker.isArea, strong: true)
        }
        for tag in content.tags {
            let text = Text(tag.text.uppercased())
                .font(.caption2.weight(.bold)).tracking(1.1)
                .foregroundStyle(palette.secondaryInk)
            place(text, near: camera.toScreen(tag.point, in: size), gap: 0, centered: true, strong: true)
        }
        drawAuthoredLabels(in: context, occupied: &occupied)
        for marker in markers where !marker.emphasized {
            place(label(marker, selected: marker.id == content.selectedID), near: marker.screen,
                  gap: marker.isArea ? 0 : 5, centered: marker.isArea)
        }

        context.drawLayer { layer in
            layer.addFilter(.shadow(color: palette.halo, radius: 1.2))
            layer.addFilter(.shadow(color: palette.halo, radius: 1.2))
            for (resolved, rect) in labels {
                layer.draw(resolved, in: rect)
            }
        }
    }

    private func label(_ marker: Marker, selected: Bool) -> Text {
        let name = marker.isArea ? marker.name.uppercased() : marker.name
        var text = Text(name)
        if marker.emphasized {
            text = text.font(marker.isArea ? .caption.weight(.semibold) : .subheadline.weight(.semibold))
                .foregroundStyle(selected ? palette.pin : (marker.kind == .water ? palette.waterInk : palette.ink))
        } else {
            text = text.font(marker.isArea ? .caption2 : .caption)
                .foregroundStyle(marker.kind == .water ? palette.waterInk : palette.secondaryInk)
        }
        if marker.isArea { text = text.tracking(1.2) }
        if marker.kind == .water { text = text.italic() }
        return text
    }

    /// Returns the rect the pin occupies so labels avoid it.
    private func drawPin(_ marker: Marker, in context: GraphicsContext) -> CGRect? {
        let color = marker.tint ?? (marker.kind == .water ? palette.river : palette.pin)
        let p = marker.screen
        if marker.id == content.selectedID {
            let ring = CGRect(x: p.x - 11, y: p.y - 11, width: 22, height: 22)
            context.stroke(Path(ellipseIn: ring), with: .color(color), lineWidth: 2)
        }
        if marker.isArea {
            let area = CGRect(x: p.x - 14, y: p.y - 14, width: 28, height: 28)
            context.fill(Path(ellipseIn: area), with: .color(color.opacity(0.12)))
            context.stroke(Path(ellipseIn: area), with: .color(color.opacity(0.55)),
                           style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            return nil  // Areas are labeled across their middle.
        }
        let dot = CGRect(x: p.x - 5.5, y: p.y - 5.5, width: 11, height: 11)
        context.fill(Path(ellipseIn: dot), with: .color(color))
        context.stroke(Path(ellipseIn: dot), with: .color(palette.pinOutline), lineWidth: 1.6)
        return dot.insetBy(dx: -1, dy: -1)
    }

    private func drawBasemap(in context: GraphicsContext) {
        guard let paths = library.paths else { return }
        var map = context
        map.concatenate(camera.transform(in: size))
        let unit = 1 / camera.scale
        let fine = camera.scale >= 45

        // Graticule every 5° (1° up close), faint.
        let step: Double = camera.scale > 180 ? 1 : 5
        let visible = camera.visibleRect(in: size)
        var grid = Path()
        var lon = (floor(visible.minX / MapProjection.lonScale / step) * step)
        while lon * MapProjection.lonScale <= visible.maxX {
            let x = lon * MapProjection.lonScale
            grid.move(to: CGPoint(x: x, y: visible.minY))
            grid.addLine(to: CGPoint(x: x, y: visible.maxY))
            lon += step
        }
        var lat = floor(-visible.maxY / step) * step
        while -lat >= visible.minY {
            grid.move(to: CGPoint(x: visible.minX, y: -lat))
            grid.addLine(to: CGPoint(x: visible.maxX, y: -lat))
            lat += step
        }
        map.stroke(grid, with: .color(palette.graticule), lineWidth: unit * 0.8)

        let land = fine ? paths.landFine : paths.landCoarse
        map.fill(land, with: .color(palette.land), style: FillStyle(eoFill: true))
        map.stroke(land, with: .color(palette.coast), style: StrokeStyle(lineWidth: unit * 0.9, lineJoin: .round))
        if fine {
            map.stroke(paths.riversMajorFine, with: .color(palette.river),
                       style: StrokeStyle(lineWidth: unit * (camera.scale > 150 ? 1.8 : 1.3), lineCap: .round, lineJoin: .round))
            if camera.scale > 110 {
                map.stroke(paths.riversMinorFine, with: .color(palette.river.opacity(0.8)),
                           style: StrokeStyle(lineWidth: unit * 0.9, lineCap: .round, lineJoin: .round))
            }
        } else {
            map.stroke(paths.riversCoarse, with: .color(palette.river),
                       style: StrokeStyle(lineWidth: unit * 1.0, lineCap: .round, lineJoin: .round))
        }
        let lakes = fine ? paths.lakesFine : paths.lakesCoarse
        map.fill(lakes, with: .color(palette.water), style: FillStyle(eoFill: true))
        map.stroke(lakes, with: .color(palette.coast), lineWidth: unit * 0.7)
    }

    private func drawRoutes(in context: GraphicsContext) {
        for route in content.routes where route.points.count > 1 {
            let screen = route.points.map { camera.toScreen($0, in: size) }
            var path = Path()
            path.addLines(screen)
            context.stroke(path, with: .color(palette.halo), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
            context.stroke(path, with: .color(route.color), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            // Direction arrows midway along longer legs.
            for (a, b) in zip(screen, screen.dropFirst()) {
                let dx = b.x - a.x, dy = b.y - a.y
                let length = hypot(dx, dy)
                guard length > 44 else { continue }
                let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                let ux = dx / length, uy = dy / length
                var arrow = Path()
                arrow.move(to: CGPoint(x: mid.x + ux * 6, y: mid.y + uy * 6))
                arrow.addLine(to: CGPoint(x: mid.x - ux * 5 - uy * 5, y: mid.y - uy * 5 + ux * 5))
                arrow.addLine(to: CGPoint(x: mid.x - ux * 5 + uy * 5, y: mid.y - uy * 5 - ux * 5))
                arrow.closeSubpath()
                context.fill(arrow, with: .color(route.color))
            }
        }
    }

    private func drawAuthoredLabels(in context: GraphicsContext, occupied: inout [CGRect]) {
        let bounds = CGRect(origin: .zero, size: size)
        for label in library.labels where camera.scale >= label.minimumScale {
            // Close-up labels (Dead Sea, Jordan) would clutter the overview and vice versa.
            if label.minimumScale == 0 && camera.scale > 90 { continue }
            let point = camera.toScreen(MapProjection.point(lon: label.longitude, lat: label.latitude), in: size)
            guard bounds.insetBy(dx: -60, dy: -20).contains(point) else { continue }
            let color = label.kind == .land ? palette.secondaryInk.opacity(0.75) : palette.waterInk
            var text = Text(label.kind == .land ? label.text.uppercased() : label.text)
                .font(label.kind == .sea ? .callout : .caption)
                .foregroundStyle(color)
            text = label.kind == .land ? text.tracking(2) : text.italic()
            let resolved = context.resolve(text)
            let measured = resolved.measure(in: CGSize(width: 400, height: 60))
            let radians = -label.angle * .pi / 180
            let w = abs(measured.width * cos(radians)) + abs(measured.height * sin(radians))
            let h = abs(measured.width * sin(radians)) + abs(measured.height * cos(radians))
            let rect = CGRect(x: point.x - w / 2, y: point.y - h / 2, width: w, height: h)
            guard !occupied.contains(where: { $0.intersects(rect) }) else { continue }
            occupied.append(rect)
            var copy = context
            copy.translateBy(x: point.x, y: point.y)
            copy.rotate(by: .radians(radians))
            copy.draw(resolved, at: .zero, anchor: .center)
            if let sub = label.subtitle, camera.scale > 40 {
                let subtitle = copy.resolve(Text(sub).font(.caption2).italic().foregroundStyle(color.opacity(0.8)))
                copy.draw(subtitle, at: CGPoint(x: 0, y: measured.height * 0.85), anchor: .center)
            }
        }
    }
}
