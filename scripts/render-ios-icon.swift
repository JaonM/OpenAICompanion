import AppKit

// Opaque artwork: iOS supplies the rounded App icon mask.
let size = 1024
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let colors = [CGColor(red: 0.13, green: 0.37, blue: 0.91, alpha: 1),
              CGColor(red: 0.07, green: 0.20, blue: 0.57, alpha: 1)]
let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: size), end: CGPoint(x: size, y: 0), options: [])
context.move(to: CGPoint(x: 512, y: 800))
context.addCurve(to: CGPoint(x: 800, y: 512), control1: CGPoint(x: 550, y: 594), control2: CGPoint(x: 594, y: 550))
context.addCurve(to: CGPoint(x: 512, y: 224), control1: CGPoint(x: 594, y: 474), control2: CGPoint(x: 550, y: 430))
context.addCurve(to: CGPoint(x: 224, y: 512), control1: CGPoint(x: 474, y: 430), control2: CGPoint(x: 430, y: 474))
context.addCurve(to: CGPoint(x: 512, y: 800), control1: CGPoint(x: 430, y: 550), control2: CGPoint(x: 474, y: 594))
context.closePath()
context.setFillColor(CGColor(gray: 1, alpha: 1))
context.fillPath()
let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
