import CoreGraphics
import Foundation

/// Paints a card's ground: gradients, textures and patterns, all made here from code — no image
/// files, nothing anyone else holds a licence to. `ShareBackdrop.kt` paints the same grounds on
/// Android from the same recipe (same hash, same noise, same numbers), so keep the two in step.
///
/// Everything is laid out in card points (1080 on the long side) and drawn as vectors, gradients and
/// small generated tiles. Fine detail (grain, weave) is generated per output pixel, so an export at
/// 2× is as crisp as the preview; the slow parts (noise fields, contour lines) depend only on the
/// ground and the card's shape and are cached.
nonisolated enum ShareBackdrop {
    // MARK: Public

    /// The ground as an image of `size` points at `pixelsPerPoint` (cached).
    static func image(_ background: ShareBackground, size: CGSize, pixelsPerPoint: CGFloat) -> CGImage? {
        let width = max(1, Int((size.width * pixelsPerPoint).rounded()))
        let height = max(1, Int((size.height * pixelsPerPoint).rounded()))
        let key = "\(background.rawValue) \(Int(size.width))x\(Int(size.height)) \(width)x\(height)" as NSString
        if let cached = images.object(forKey: key) { return cached }
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        // Points, y down.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: CGFloat(width) / size.width, y: -CGFloat(height) / size.height)
        context.interpolationQuality = .high
        draw(background, in: context, size: size, pixelsPerPoint: CGFloat(width) / size.width)
        guard let image = context.makeImage() else { return nil }
        images.setObject(image, forKey: key, cost: width * height * 4)
        return image
    }

    /// The ground's average and its lightest and darkest ninth-by-ninth patches (cached).
    static func stats(_ background: ShareBackground) -> ShareBackdropStats {
        if let cached = statsCache.withLock({ $0[background] }) { return cached }
        let size = CGSize(width: 1080, height: 1080)
        let side = 108, block = 12
        var result = ShareBackdropStats(mean: background.colors[0], lightest: background.colors[0], darkest: background.colors[0])
        if let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                   space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
           let image = Self.image(background, size: size, pixelsPerPoint: CGFloat(side) / size.width) {
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            if let data = context.data?.assumingMemoryBound(to: UInt8.self) {
                var total = (0.0, 0.0, 0.0)
                var light = (lum: -1.0, color: UInt32(0)), dark = (lum: 2.0, color: UInt32(0))
                for by in 0..<(side / block) {
                    for bx in 0..<(side / block) {
                        var sum = (0.0, 0.0, 0.0)
                        for y in (by * block)..<((by + 1) * block) {
                            for x in (bx * block)..<((bx + 1) * block) {
                                let i = (y * side + x) * 4
                                sum.0 += Double(data[i]); sum.1 += Double(data[i + 1]); sum.2 += Double(data[i + 2])
                            }
                        }
                        let n = Double(block * block)
                        let color = rgb(sum.0 / n, sum.1 / n, sum.2 / n)
                        let lum = ShareContrast.luminance(color)
                        if lum > light.lum { light = (lum, color) }
                        if lum < dark.lum { dark = (lum, color) }
                        total.0 += sum.0; total.1 += sum.1; total.2 += sum.2
                    }
                }
                let n = Double(side * side)
                result = ShareBackdropStats(mean: rgb(total.0 / n, total.1 / n, total.2 / n), lightest: light.color, darkest: dark.color)
            }
        }
        statsCache.withLock { $0[background] = result }
        return result
    }

    /// Paints `background` over a card of `size` points into `context`, whose transform maps points
    /// with y pointing down at `pixelsPerPoint` device pixels each.
    static func draw(_ background: ShareBackground, in context: CGContext, size: CGSize, pixelsPerPoint: CGFloat) {
        let seed = seed(for: background)
        fillGround(background, context, size)
        switch background.texture {
        case .clean:
            if background.colors.count > 1 { tile(grainTile(seed: seed, amount: 0.012), context, size, pixelsPerPoint) }
        case .paper:
            paper(seed, context, size, pixelsPerPoint, mottle: 1)
            vignette(context, size, alpha: 0.07)
        case .linen:
            mottle(seed, context, size, amount: 0.5)
            tile(weaveTile(.linen, seed: seed, pixelsPerPoint: pixelsPerPoint), context, size, pixelsPerPoint)
            tile(grainTile(seed: seed, amount: 0.025), context, size, pixelsPerPoint)
        case .canvas:
            mottle(seed, context, size, amount: 0.8)
            tile(weaveTile(.canvas, seed: seed, pixelsPerPoint: pixelsPerPoint), context, size, pixelsPerPoint)
            tile(grainTile(seed: seed, amount: 0.035), context, size, pixelsPerPoint)
            vignette(context, size, alpha: 0.06)
        case .grain:
            tile(grainTile(seed: seed, amount: 0.10), context, size, pixelsPerPoint)
            vignette(context, size, alpha: 0.35)
        case .watercolor(let pigments):
            if let wash = watercolorImage(pigments, seed: seed, size: size) {
                context.saveGState()
                context.setBlendMode(.multiply)
                drawField(wash, context, size)
                context.restoreGState()
            }
            paper(seed, context, size, pixelsPerPoint, mottle: 0.5)
        case .bokeh(let lights):
            bokeh(lights, seed: seed, context, size)
            vignette(context, size, alpha: 0.28)
            tile(grainTile(seed: seed, amount: 0.03), context, size, pixelsPerPoint)
        case .glow:
            glow(context, size)
            vignette(context, size, alpha: 0.22)
            tile(grainTile(seed: seed, amount: 0.06), context, size, pixelsPerPoint)
        case .lattice(let line):
            lattice(line, context, size)
            tile(grainTile(seed: seed, amount: 0.015), context, size, pixelsPerPoint)
        case .contour(let line):
            contour(line, seed: seed, context, size)
            tile(grainTile(seed: seed, amount: 0.015), context, size, pixelsPerPoint)
        }
    }

    // MARK: Noise (identical in ShareBackdrop.kt)

    /// A 32-bit integer hash of a lattice point.
    static func hash(_ x: Int32, _ y: Int32, _ seed: Int32) -> UInt32 {
        var h = UInt32(bitPattern: seed) &* 0x9E37_79B1
        h ^= UInt32(bitPattern: x) &* 0x85EB_CA77
        h = (h << 13) | (h >> 19)
        h ^= UInt32(bitPattern: y) &* 0xC2B2_AE3D
        h = (h << 17) | (h >> 15)
        h = h &* 0x27D4_EB2F
        h ^= h >> 15
        h = h &* 0x85EB_CA77
        h ^= h >> 13
        return h
    }

    /// The hash as 0 ..< 1.
    static func unit(_ x: Int32, _ y: Int32, _ seed: Int32) -> Float {
        Float(hash(x, y, seed) >> 8) / 16_777_216
    }

    /// Smooth value noise, 0 ... 1.
    static func noise(_ x: Float, _ y: Float, _ seed: Int32) -> Float {
        let fx = x.rounded(.down), fy = y.rounded(.down)
        let xi = Int32(fx), yi = Int32(fy)
        let tx = x - fx, ty = y - fy
        let sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty)
        let a = unit(xi, yi, seed), b = unit(xi &+ 1, yi, seed)
        let c = unit(xi, yi &+ 1, seed), d = unit(xi &+ 1, yi &+ 1, seed)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    /// Fractal noise: `octaves` of `noise`, each twice the frequency and half the weight.
    static func fbm(_ x: Float, _ y: Float, octaves: Int, seed: Int32) -> Float {
        var sum: Float = 0, weight: Float = 0.5, total: Float = 0, frequency: Float = 1
        for octave in 0..<octaves {
            sum += weight * noise(x * frequency, y * frequency, seed &+ Int32(octave) &* 31)
            total += weight
            weight *= 0.5
            frequency *= 2
        }
        return sum / total
    }

    /// A ground's seed: a hash of its name, so adding a ground never reshuffles another's texture.
    static func seed(for background: ShareBackground) -> Int32 {
        background.rawValue.utf8.reduce(Int32(7)) { $0 &* 31 &+ Int32($1) }
    }

    static func smoothstep(_ a: Float, _ b: Float, _ x: Float) -> Float {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    // MARK: Ground

    private static func fillGround(_ background: ShareBackground, _ context: CGContext, _ size: CGSize) {
        let colors = background.colors
        let rect = CGRect(origin: .zero, size: size)
        if colors.count == 1 {
            context.setFillColor(cgColor(colors[0]))
            context.fill(rect)
            return
        }
        let gradient = CGGradient(colorsSpace: sRGB, colors: colors.map { cgColor($0) } as CFArray, locations: nil)!
        switch background.gradient {
        case .vertical:
            context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height),
                                       options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        case .diagonal:
            context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height),
                                       options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        case .radial:
            let center = CGPoint(x: size.width * 0.5, y: size.height * 0.42)
            context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center,
                                       endRadius: hypot(size.width, size.height) * 0.55, options: [.drawsAfterEndLocation])
        }
    }

    /// Darkens toward the corners.
    private static func vignette(_ context: CGContext, _ size: CGSize, alpha: CGFloat) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let radius = hypot(size.width, size.height) / 2
        let gradient = CGGradient(colorsSpace: sRGB, colors: [cgColor(0, alpha: 0), cgColor(0, alpha: 0), cgColor(0, alpha: alpha)] as CFArray,
                                  locations: [0, 0.45, 1])!
        context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: radius,
                                   options: [.drawsAfterEndLocation])
    }

    // MARK: Tiles

    /// Fills the card with `image` repeated, one image pixel per device pixel.
    private static func tile(_ image: CGImage?, _ context: CGContext, _ size: CGSize, _ pixelsPerPoint: CGFloat) {
        guard let image else { return }
        let tile = CGRect(x: 0, y: 0, width: CGFloat(image.width) / pixelsPerPoint, height: CGFloat(image.height) / pixelsPerPoint)
        context.saveGState()
        context.clip(to: CGRect(origin: .zero, size: size))
        context.interpolationQuality = .none
        context.draw(image, in: tile, byTiling: true)
        context.restoreGState()
    }

    /// 256 × 256 pixels of monochrome grain: white or black, at up to `amount` opacity.
    static func grainTile(seed: Int32, amount: Float) -> CGImage? {
        let key = "grain \(seed) \(amount)" as NSString
        if let cached = tiles.object(forKey: key) { return cached }
        let side = 256
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        for y in 0..<side {
            for x in 0..<side {
                let n = unit(Int32(x), Int32(y), seed &+ 101) + unit(Int32(x), Int32(y), seed &+ 202) - 1
                put(&pixels, (y * side + x) * 4, n * amount)
            }
        }
        let image = makeImage(pixels, side, side)
        if let image { tiles.setObject(image, forKey: key) }
        return image
    }

    enum Weave { case linen, canvas }

    /// Sixteen threads each way, in device pixels: a plain weave (linen) or a coarser over-and-under
    /// one (canvas). Threads finer than about two pixels fade out rather than shimmer.
    static func weaveTile(_ weave: Weave, seed: Int32, pixelsPerPoint: CGFloat) -> CGImage? {
        let period: Float = weave == .linen ? 3.0 : 5.0
        let threads = 16
        let side = max(1, Int((Float(threads) * period * Float(pixelsPerPoint)).rounded()))
        let pitch = Float(side) / Float(threads)
        let fade = min(1, max(0, (pitch - 1.5) / 1.5))
        guard fade > 0 else { return nil }
        let key = "weave \(weave) \(seed) \(side)" as NSString
        if let cached = tiles.object(forKey: key) { return cached }
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        for y in 0..<side {
            let v = (Float(y) + 0.5) / pitch
            let j = Int32(Int(v) % threads)
            let across = sin(Float.pi * (v - v.rounded(.down)))
            let row = unit(j, 0, seed &+ 5)
            for x in 0..<side {
                let u = (Float(x) + 0.5) / pitch
                let i = Int32(Int(u) % threads)
                let along = sin(Float.pi * (u - u.rounded(.down)))
                let column = unit(i, 1, seed &+ 5)
                let d: Float
                switch weave {
                case .linen:
                    let value = 0.5 * (across * (0.7 + 0.6 * row)) + 0.5 * (along * (0.7 + 0.6 * column))
                    d = (value - 0.55) * 0.11
                case .canvas:
                    let over = (i + j) % 2 == 0
                    let shade = over ? across * (0.8 + 0.4 * row) : along * (0.8 + 0.4 * column)
                    let gap = (1 - across) * (1 - along)
                    d = (shade * 0.85 - gap * 0.4 - 0.45) * 0.12
                }
                put(&pixels, (y * side + x) * 4, d * fade)
            }
        }
        let image = makeImage(pixels, side, side)
        if let image { tiles.setObject(image, forKey: key) }
        return image
    }

    /// Writes a signed brightness change as premultiplied white (lighter) or black (darker).
    private static func put(_ pixels: inout [UInt8], _ i: Int, _ d: Float) {
        let alpha = UInt8(min(255, (abs(d) * 255).rounded()))
        let value: UInt8 = d > 0 ? alpha : 0
        pixels[i] = value; pixels[i + 1] = value; pixels[i + 2] = value; pixels[i + 3] = alpha
    }

    // MARK: Fields (in card points, cached per ground and shape)

    /// A low-resolution image whose pixel (c, r) is the field at (c, r) × `step` points, stretched
    /// smoothly over the card.
    private static func drawField(_ image: CGImage, _ context: CGContext, _ size: CGSize) {
        let step = fieldStep(image, size)
        let rect = CGRect(x: -step / 2, y: -step / 2, width: CGFloat(image.width) * step, height: CGFloat(image.height) * step)
        context.saveGState()
        context.interpolationQuality = .high
        // Images draw bottom-up; the card is y-down, so flip about the rectangle.
        context.translateBy(x: 0, y: rect.minY + rect.maxY)
        context.scaleBy(x: 1, y: -1)
        context.draw(image, in: rect)
        context.restoreGState()
    }

    private static func fieldStep(_ image: CGImage, _ size: CGSize) -> CGFloat {
        size.width / CGFloat(image.width - 1)
    }

    private static func gridSize(_ size: CGSize, step: CGFloat) -> (columns: Int, rows: Int, step: CGFloat) {
        let columns = Int((size.width / step).rounded(.up)) + 1
        // Exactly spans the width, so `fieldStep` can recover it.
        let exact = size.width / CGFloat(columns - 1)
        let rows = Int((size.height / exact).rounded(.up)) + 1
        return (columns, rows, exact)
    }

    /// Paper: a mottled brightness field, a few fibres and grain.
    private static func paper(_ seed: Int32, _ context: CGContext, _ size: CGSize, _ pixelsPerPoint: CGFloat, mottle amount: Float) {
        mottle(seed, context, size, amount: amount)
        context.saveGState()
        context.setLineCap(.round)
        context.setLineWidth(0.5)
        let short = min(size.width, size.height) / 1080
        for i in 0..<Int32(80) {
            let x = CGFloat(unit(i, 0, seed &+ 9)) * size.width
            let y = CGFloat(unit(i, 1, seed &+ 9)) * size.height
            let angle = CGFloat(unit(i, 2, seed &+ 9)) * 2 * .pi
            let length = (8 + 30 * CGFloat(unit(i, 3, seed &+ 9))) * max(0.6, short)
            let bend = (CGFloat(unit(i, 4, seed &+ 9)) - 0.5) * length * 0.6
            let dx = cos(angle) * length, dy = sin(angle) * length
            context.move(to: CGPoint(x: x, y: y))
            context.addQuadCurve(to: CGPoint(x: x + dx, y: y + dy),
                                 control: CGPoint(x: x + dx / 2 - dy / length * bend, y: y + dy / 2 + dx / length * bend))
            let dark = unit(i, 5, seed &+ 9) < 0.6
            context.setStrokeColor(dark ? cgColor(0x5A4630, alpha: 0.07) : cgColor(0xFFFFFF, alpha: 0.12))
            context.strokePath()
        }
        context.restoreGState()
        tile(grainTile(seed: seed, amount: 0.04), context, size, pixelsPerPoint)
    }

    /// Soft blotches of lighter and darker, as in handmade paper or unevenly dyed cloth.
    private static func mottle(_ seed: Int32, _ context: CGContext, _ size: CGSize, amount: Float) {
        guard let image = mottleImage(seed: seed, size: size) else { return }
        context.saveGState()
        context.setAlpha(CGFloat(amount))
        drawField(image, context, size)
        context.restoreGState()
    }

    private static func mottleImage(seed: Int32, size: CGSize) -> CGImage? {
        let key = "mottle \(seed) \(Int(size.width))x\(Int(size.height))" as NSString
        if let cached = fields.object(forKey: key) { return cached }
        let grid = gridSize(size, step: 6)
        var pixels = [UInt8](repeating: 0, count: grid.columns * grid.rows * 4)
        for r in 0..<grid.rows {
            for c in 0..<grid.columns {
                let x = Float(CGFloat(c) * grid.step), y = Float(CGFloat(r) * grid.step)
                let broad = fbm(x / 110, y / 110, octaves: 4, seed: seed &+ 3)
                let fine = fbm(x / 22, y / 22, octaves: 2, seed: seed &+ 4)
                put(&pixels, (r * grid.columns + c) * 4, (broad - 0.5) * 0.10 + (fine - 0.5) * 0.04)
            }
        }
        let image = makeImage(pixels, grid.columns, grid.rows)
        if let image { fields.setObject(image, forKey: key) }
        return image
    }

    /// Three washes of pigment, heavier toward the corners, with darker edges where each dried.
    /// Drawn with multiply: each pixel is the light the pigments let through.
    static func watercolorImage(_ pigments: [UInt32], seed: Int32, size: CGSize) -> CGImage? {
        let key = "wash \(seed) \(Int(size.width))x\(Int(size.height))" as NSString
        if let cached = fields.object(forKey: key) { return cached }
        let grid = gridSize(size, step: 5)
        let tints = pigments.map { (Float(($0 >> 16) & 0xFF) / 255, Float(($0 >> 8) & 0xFF) / 255, Float($0 & 0xFF) / 255) }
        let halfW = Float(size.width) / 2, halfH = Float(size.height) / 2
        var pixels = [UInt8](repeating: 255, count: grid.columns * grid.rows * 4)
        for r in 0..<grid.rows {
            for c in 0..<grid.columns {
                let x = Float(CGFloat(c) * grid.step), y = Float(CGFloat(r) * grid.step)
                let dx = (x - halfW) / halfW, dy = (y - halfH) / halfH
                let corner = smoothstep(0.2, 0.75, (dx * dx + dy * dy).squareRoot() / 1.4142)
                let weight = 0.2 + 0.8 * corner
                var light: (Float, Float, Float) = (1, 1, 1)
                for (k, tint) in tints.enumerated() {
                    let s = seed &+ Int32(k) &* 13
                    let wx = (fbm(x / 360, y / 360, octaves: 2, seed: s &+ 20) - 0.5) * 120
                    let wy = (fbm(x / 360 + 7.3, y / 360 + 1.9, octaves: 2, seed: s &+ 40) - 0.5) * 120
                    let f = fbm((x + wx) / 420, (y + wy) / 420, octaves: 4, seed: s)
                    var a = smoothstep(0.52, 0.7, f) * 0.42
                    if f > 0.52 { a += exp(-pow((f - 0.535) / 0.02, 2)) * 0.12 }
                    a = min(1, a * weight)
                    light.0 *= 1 - a * (1 - tint.0)
                    light.1 *= 1 - a * (1 - tint.1)
                    light.2 *= 1 - a * (1 - tint.2)
                }
                let i = (r * grid.columns + c) * 4
                pixels[i] = UInt8((light.0 * 255).rounded())
                pixels[i + 1] = UInt8((light.1 * 255).rounded())
                pixels[i + 2] = UInt8((light.2 * 255).rounded())
            }
        }
        let image = makeImage(pixels, grid.columns, grid.rows)
        if let image { fields.setObject(image, forKey: key) }
        return image
    }

    // MARK: Light

    /// Discs of out-of-focus light, brighter at the rim, added with screen.
    private static func bokeh(_ lights: [UInt32], seed: Int32, _ context: CGContext, _ size: CGSize) {
        context.saveGState()
        context.setBlendMode(.screen)
        let scale = min(size.width, size.height) / 1080
        for i in 0..<Int32(36) {
            let center = CGPoint(x: (CGFloat(unit(i, 0, seed)) * 1.1 - 0.05) * size.width,
                                 y: (CGFloat(unit(i, 1, seed)) * 1.1 - 0.05) * size.height)
            let u = CGFloat(unit(i, 2, seed))
            let radius = (16 + 95 * u * u) * scale
            let light = lights[Int(unit(i, 3, seed) * Float(lights.count)) % lights.count]
            let alpha = 0.12 + 0.22 * CGFloat(unit(i, 4, seed))
            let gradient = CGGradient(colorsSpace: sRGB,
                                      colors: [cgColor(light, alpha: alpha * 0.55), cgColor(light, alpha: alpha * 0.75),
                                               cgColor(light, alpha: alpha), cgColor(light, alpha: 0)] as CFArray,
                                      locations: [0, 0.8, 0.93, 1])!
            context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: radius, options: [])
        }
        context.restoreGState()
    }

    /// Warm light leaking in from the top right and bottom left, added with screen.
    private static func glow(_ context: CGContext, _ size: CGSize) {
        let long = max(size.width, size.height)
        let leaks: [(x: CGFloat, y: CGFloat, radius: CGFloat, color: UInt32, alpha: CGFloat)] = [
            (1.02, -0.05, 0.95, 0xFF8A3D, 0.7), (-0.08, 1.04, 0.8, 0xE8486B, 0.42), (0.9, 1.08, 0.45, 0xFFC46B, 0.3),
        ]
        context.saveGState()
        context.setBlendMode(.screen)
        for leak in leaks {
            let center = CGPoint(x: leak.x * size.width, y: leak.y * size.height)
            let gradient = CGGradient(colorsSpace: sRGB,
                                      colors: [cgColor(leak.color, alpha: leak.alpha), cgColor(leak.color, alpha: leak.alpha * 0.45),
                                               cgColor(leak.color, alpha: 0)] as CFArray,
                                      locations: [0, 0.45, 1])!
            context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center,
                                       endRadius: leak.radius * long, options: [])
        }
        context.restoreGState()
    }

    // MARK: Lines

    /// Three families of fine lines 60° apart, fading out behind the text.
    private static func lattice(_ line: UInt32, _ context: CGContext, _ size: CGSize) {
        let spacing: CGFloat = 56
        let reach = hypot(size.width, size.height)
        let path = CGMutablePath()
        for degrees in [0.0, 60.0, 120.0] {
            let angle = degrees * .pi / 180
            let direction = CGPoint(x: cos(angle), y: sin(angle))
            let normal = CGPoint(x: -direction.y, y: direction.x)
            let corners = [CGPoint.zero, CGPoint(x: size.width, y: 0), CGPoint(x: 0, y: size.height),
                           CGPoint(x: size.width, y: size.height)].map { $0.x * normal.x + $0.y * normal.y }
            // Offset by half a spacing so no line runs exactly along an edge.
            var k = ((corners.min()! / spacing).rounded(.down) + 0.5) * spacing
            while k <= corners.max()! {
                let base = CGPoint(x: normal.x * k, y: normal.y * k)
                path.move(to: CGPoint(x: base.x - direction.x * reach, y: base.y - direction.y * reach))
                path.addLine(to: CGPoint(x: base.x + direction.x * reach, y: base.y + direction.y * reach))
                k += spacing
            }
        }
        fadedLines([(path, 1.0, 0.26)], color: line, centerFade: 0.85, context, size)
    }

    /// Contour lines of a gentle noise landscape; every fifth is heavier, as on a map.
    private static func contour(_ line: UInt32, seed: Int32, _ context: CGContext, _ size: CGSize) {
        let (thin, thick) = contourPaths(seed: seed, size: size)
        fadedLines([(thin, 0.8, 0.3), (thick, 1.3, 0.42)], color: line, centerFade: 0.75, context, size)
    }

    static func contourPaths(seed: Int32, size: CGSize) -> (thin: CGPath, thick: CGPath) {
        let key = "\(seed) \(Int(size.width))x\(Int(size.height))"
        if let cached = contourCache.withLock({ $0[key] }) { return cached }
        let grid = gridSize(size, step: 6)
        var field = [Float](repeating: 0, count: grid.columns * grid.rows)
        for r in 0..<grid.rows {
            for c in 0..<grid.columns {
                field[r * grid.columns + c] = fbm(Float(CGFloat(c) * grid.step) / 280, Float(CGFloat(r) * grid.step) / 280,
                                                  octaves: 3, seed: seed &+ 7)
            }
        }
        let low = field.min() ?? 0, high = field.max() ?? 1
        let levels = 18
        let thin = CGMutablePath(), thick = CGMutablePath()
        for level in 1..<levels {
            let iso = low + (high - low) * Float(level) / Float(levels)
            let path = level % 5 == 0 ? thick : thin
            marchingSquares(field, grid.columns, grid.rows, grid.step, iso, path)
        }
        let result = (thin as CGPath, thick as CGPath)
        contourCache.withLock { $0[key] = result }
        return result
    }

    /// Adds the iso-line of `field` at `iso` to `path` as segments (one or two per grid cell).
    private static func marchingSquares(_ field: [Float], _ columns: Int, _ rows: Int, _ step: CGFloat, _ iso: Float,
                                        _ path: CGMutablePath) {
        func point(_ c0: Int, _ r0: Int, _ c1: Int, _ r1: Int) -> CGPoint {
            let a = field[r0 * columns + c0], b = field[r1 * columns + c1]
            let t = CGFloat(abs(b - a) < 1e-9 ? 0.5 : (iso - a) / (b - a))
            return CGPoint(x: (CGFloat(c0) + CGFloat(c1 - c0) * t) * step, y: (CGFloat(r0) + CGFloat(r1 - r0) * t) * step)
        }
        for r in 0..<(rows - 1) {
            for c in 0..<(columns - 1) {
                var index = 0
                if field[r * columns + c] > iso { index |= 1 }
                if field[r * columns + c + 1] > iso { index |= 2 }
                if field[(r + 1) * columns + c + 1] > iso { index |= 4 }
                if field[(r + 1) * columns + c] > iso { index |= 8 }
                if index == 0 || index == 15 { continue }
                // Edges: top (c,r)-(c+1,r), right, bottom, left.
                let top = { point(c, r, c + 1, r) }, right = { point(c + 1, r, c + 1, r + 1) }
                let bottom = { point(c, r + 1, c + 1, r + 1) }, left = { point(c, r, c, r + 1) }
                func segment(_ a: CGPoint, _ b: CGPoint) { path.move(to: a); path.addLine(to: b) }
                switch index {
                case 1, 14: segment(left(), top())
                case 2, 13: segment(top(), right())
                case 3, 12: segment(left(), right())
                case 4, 11: segment(right(), bottom())
                case 5: segment(left(), top()); segment(right(), bottom())
                case 6, 9: segment(top(), bottom())
                case 7, 8: segment(left(), bottom())
                case 10: segment(top(), right()); segment(left(), bottom())
                default: break
                }
            }
        }
    }

    /// Strokes `paths` (path, width, opacity) in `color`, then erases them toward the centre so the
    /// pattern frames the text instead of running through it.
    private static func fadedLines(_ paths: [(CGPath, CGFloat, CGFloat)], color: UInt32, centerFade: CGFloat,
                                   _ context: CGContext, _ size: CGSize) {
        context.saveGState()
        context.clip(to: CGRect(origin: .zero, size: size))
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        context.setLineCap(.round)
        for (path, width, alpha) in paths {
            context.addPath(path)
            context.setLineWidth(width)
            context.setStrokeColor(cgColor(color, alpha: alpha))
            context.strokePath()
        }
        context.setBlendMode(.destinationOut)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let gradient = CGGradient(colorsSpace: sRGB, colors: [cgColor(0, alpha: centerFade), cgColor(0, alpha: centerFade * 0.6),
                                                             cgColor(0, alpha: 0)] as CFArray,
                                  locations: [0, 0.5, 1])!
        context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center,
                                   endRadius: hypot(size.width, size.height) * 0.48, options: [])
        context.endTransparencyLayer()
        context.restoreGState()
    }

    // MARK: Helpers

    private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
    nonisolated(unsafe) private static let images: NSCache<NSString, CGImage> = {
        let cache = NSCache<NSString, CGImage>()
        cache.totalCostLimit = 80 * 1024 * 1024
        return cache
    }()
    nonisolated(unsafe) private static let tiles = NSCache<NSString, CGImage>()
    nonisolated(unsafe) private static let fields = NSCache<NSString, CGImage>()
    private static let statsCache = Locked<[ShareBackground: ShareBackdropStats]>([:])
    private static let contourCache = Locked<[String: (thin: CGPath, thick: CGPath)]>([:])

    static func cgColor(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
        CGColor(colorSpace: sRGB, components: [CGFloat((hex >> 16) & 0xFF) / 255, CGFloat((hex >> 8) & 0xFF) / 255,
                                               CGFloat(hex & 0xFF) / 255, alpha])!
    }

    private static func rgb(_ r: Double, _ g: Double, _ b: Double) -> UInt32 {
        func c(_ v: Double) -> UInt32 { UInt32(max(0, min(255, v.rounded()))) }
        return c(r) << 16 | c(g) << 8 | c(b)
    }

    private static func makeImage(_ pixels: [UInt8], _ width: Int, _ height: Int) -> CGImage? {
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: sRGB, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

/// A value behind a lock, for the caches above.
nonisolated final class Locked<Value>: @unchecked Sendable {
    private var value: Value
    private let lock = NSLock()
    init(_ value: Value) { self.value = value }
    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }
}
