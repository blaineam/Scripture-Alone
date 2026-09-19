// Renders the placeholder app icon: an open book on a deep crimson field.
// swift Tools/generate_icon.swift ScriptureAlone/Resources/Assets.xcassets/AppIcon.appiconset
import AppKit

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
let size = 1024
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let rect = NSRect(x: 0, y: 0, width: size, height: size)
NSGradient(colors: [NSColor(red: 0.55, green: 0.10, blue: 0.10, alpha: 1),
                    NSColor(red: 0.28, green: 0.04, blue: 0.06, alpha: 1)])!.draw(in: rect, angle: -90)
let config = NSImage.SymbolConfiguration(pointSize: 520, weight: .light)
    .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(red: 0.98, green: 0.93, blue: 0.82, alpha: 1)]))
if let symbol = NSImage(systemSymbolName: "book.pages", accessibilityDescription: nil)?.withSymbolConfiguration(config) {
    let s = symbol.size
    symbol.draw(in: NSRect(x: (CGFloat(size) - s.width) / 2, y: (CGFloat(size) - s.height) / 2 - 10, width: s.width, height: s.height))
}
NSGraphicsContext.restoreGraphicsState()
let data = rep.representation(using: .png, properties: [:])!
try! data.write(to: URL(fileURLWithPath: out).appendingPathComponent("AppIcon-1024.png"))
print("wrote \(out)/AppIcon-1024.png")
