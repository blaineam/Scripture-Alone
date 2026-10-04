import AppKit
// Frames a screenshot in a device bezel for the user guide (docs/manual/README.md).
// frame <bezel.png> <shot.png> <ox> <oy> <radius> <outWidth> <out.png>  (transparent around the device)
//   ox, oy: the screen's top-left in the bezel art; radius: the screen's corner radius, in bezel pixels.
let a = CommandLine.arguments
let bezel = NSImage(contentsOfFile: a[1])!, shot = NSImage(contentsOfFile: a[2])!
let bz = NSBitmapImageRep(data: bezel.tiffRepresentation!)!, sr = NSBitmapImageRep(data: shot.tiffRepresentation!)!
let ox = CGFloat(Double(a[3])!), oy = CGFloat(Double(a[4])!), r = CGFloat(Double(a[5])!), outW = CGFloat(Double(a[6])!)
let BW = CGFloat(bz.pixelsWide), BH = CGFloat(bz.pixelsHigh), SW = CGFloat(sr.pixelsWide), SH = CGFloat(sr.pixelsHigh)
let s = outW / BW, outH = (BH * s).rounded()
let out = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(outW), pixelsHigh: Int(outH), bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
let ctx = NSGraphicsContext(bitmapImageRep: out)!; NSGraphicsContext.current = ctx; ctx.imageInterpolation = .high
// screen (flipped y: AppKit origin bottom-left)
let screen = NSRect(x: ox*s, y: (BH - oy - SH)*s, width: SW*s, height: SH*s)
NSGraphicsContext.saveGraphicsState()
NSBezierPath(roundedRect: screen, xRadius: r*s, yRadius: r*s).addClip()
sr.draw(in: screen, from: .zero, operation: .copy, fraction: 1, respectFlipped: false, hints: nil)
NSGraphicsContext.restoreGraphicsState()
bz.draw(in: NSRect(x: 0, y: 0, width: outW, height: outH), from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: false, hints: nil)
NSGraphicsContext.restoreGraphicsState()
// crop to the bezel's opaque bounds (the art carries transparent padding)
var minY = Int(BH), maxY = 0, minX = Int(BW), maxX = 0
for y in stride(from: 0, to: Int(BH), by: 2) { for x in stride(from: 0, to: Int(BW), by: 8) where bz.colorAt(x: x, y: y)!.alphaComponent > 0.1 { minY = min(minY, y); maxY = max(maxY, y); minX = min(minX, x); maxX = max(maxX, x) } }
let crop = CGRect(x: CGFloat(max(0, minX - 8)) * s, y: CGFloat(max(0, minY - 2)) * s, width: CGFloat(min(Int(BW), maxX + 8) - max(0, minX - 8)) * s, height: CGFloat(min(Int(BH), maxY + 2) - max(0, minY - 2)) * s).integral
let cropped = NSBitmapImageRep(cgImage: out.cgImage!.cropping(to: crop)!)
try! cropped.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[7]))
