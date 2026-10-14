import Foundation
import AppKit
import ImageIO
import UniformTypeIdentifiers

// All rectangles use the 943-pixel-wide screenshot preview coordinate system.
struct Frame {
    let path: String
    let crop: CGRect
    let caption: String
    var masks: [CGRect] = []
}
struct Demo { let name: String; let title: String; let footer: String; let frames: [Frame] }
guard CommandLine.arguments.count == 3 else {
    fatalError("Usage: swift scripts/generate-readme-demos.swift <export-root> <output-directory>")
}
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let alarm = "companion-native-physical-final-attachments/"
let health = "companion-native-health-authorized-attachments/FDC92558-04A0-40E7-96BE-4AA0EC6016B6.png"
let cross = "companion-mlx-native-passed-1010/E5C77FFA-1A66-42F3-A4CD-D60810398C5F.png"
let demos = [
    Demo(name: "ios-health", title: "健康 · 一次步数查询", footer: "只读查询 · 个人步数已遮挡", frames: [
        Frame(path: health, crop: CGRect(x:170,y:515,width:730,height:325), caption: "1 / 2   查询指标、时间区间与时区"),
        Frame(path: health, crop: CGRect(x:110,y:1190,width:800,height:492), caption: "2 / 2   查看 HealthKit 返回结果", masks:[CGRect(x:115,y:1340,width:790,height:145)])
    ]),
    Demo(name: "ios-alarm", title: "闹钟 · 创建到取消", footer: "仅管理本 App 闹钟 · 验收闹钟已取消", frames: [
        Frame(path:alarm+"5FD681DC-4633-44D8-B9EE-9D4529F09D1F.png", crop:CGRect(x:110,y:1150,width:800,height:495),caption:"1 / 3   创建：scheduled = true"),
        Frame(path:alarm+"C559623C-BB2B-47B7-8EB2-2E114B282255.png", crop:CGRect(x:110,y:1190,width:800,height:335),caption:"2 / 3   查询：列表包含刚创建的闹钟"),
        Frame(path:alarm+"CBF1A790-7A95-405F-90AC-9A04205471BB.png", crop:CGRect(x:110,y:1225,width:800,height:422),caption:"3 / 3   取消：cancelled = true")
    ]),
    Demo(name: "ios-cross-device", title: "跨设备 · iPhone → Mac", footer: "同一完成结果的局部展示 · 17 + 25 = 42", frames:[
        Frame(path:cross,crop:CGRect(x:95,y:320,width:800,height:800),caption:"1 / 2   查看路由与委托调用记录"),
        Frame(path:cross,crop:CGRect(x:35,y:1150,width:875,height:540),caption:"2 / 2   远端结果回到 iPhone 对话")
    ])
]
let width = 640, height = 800
let output = URL(fileURLWithPath:CommandLine.arguments[2])
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
func text(_ value:String, x:CGFloat, top:CGFloat, size:CGFloat, color:NSColor = .labelColor, bold:Bool = false) {
    let attrs:[NSAttributedString.Key:Any] = [.font: NSFont.systemFont(ofSize:size,weight:bold ? .semibold:.regular),.foregroundColor:color]
    (value as NSString).draw(at:NSPoint(x:x,y:CGFloat(height)-top-size*1.3),withAttributes:attrs)
}
for demo in demos {
    let gif = output.appendingPathComponent(demo.name+".gif")
    let dest = CGImageDestinationCreateWithURL(gif as CFURL,UTType.gif.identifier as CFString,demo.frames.count,nil)!
    CGImageDestinationSetProperties(dest,[kCGImagePropertyGIFDictionary:[kCGImagePropertyGIFLoopCount:0]] as CFDictionary)
    for (index, frame) in demo.frames.enumerated() {
        let source = CGImageSourceCreateWithURL(root.appendingPathComponent(frame.path) as CFURL,nil)!
        let raw = CGImageSourceCreateImageAtIndex(source,0,nil)!
        let scale = CGFloat(raw.width)/943
        let crop = raw.cropping(to:CGRect(x:frame.crop.minX*scale,y:frame.crop.minY*scale,width:frame.crop.width*scale,height:frame.crop.height*scale))!
        let ctx = CGContext(data:nil,width:width,height:height,bitsPerComponent:8,bytesPerRow:0,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(NSColor(calibratedWhite:0.975,alpha:1).cgColor); ctx.fill(CGRect(x:0,y:0,width:width,height:height))
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext:ctx,flipped:false)
        text("OpenAICompanion  /  iOS",x:28,top:22,size:17,color:.systemBlue,bold:true)
        text(demo.title,x:28,top:62,size:30,color:NSColor(calibratedWhite:0.12,alpha:1),bold:true)
        text(frame.caption,x:28,top:111,size:20,color:NSColor(calibratedWhite:0.38,alpha:1))
        let ratio = min(584/frame.crop.width,520/frame.crop.height)
        let w = frame.crop.width*ratio, h = frame.crop.height*ratio
        let x = (CGFloat(width)-w)/2, top = 162+(520-h)/2
        ctx.draw(crop,in:CGRect(x:x,y:CGFloat(height)-top-h,width:w,height:h))
        for mask in frame.masks {
            let rect = CGRect(x:x+(mask.minX-frame.crop.minX)*ratio,y:CGFloat(height)-top-(mask.maxY-frame.crop.minY)*ratio,width:mask.width*ratio,height:mask.height*ratio)
            ctx.setFillColor(NSColor(calibratedRed:0.89,green:0.93,blue:0.98,alpha:1).cgColor); ctx.fill(rect)
            text("每日日期与个人步数已遮挡",x:rect.minX+16,top:CGFloat(height)-rect.midY-14,size:23,color:.darkGray)
        }
        ctx.setFillColor(NSColor.systemBlue.cgColor); ctx.fill(CGRect(x:28,y:91,width:42,height:3))
        text(demo.footer,x:28,top:720,size:19,color:NSColor(calibratedWhite:0.28,alpha:1))
        text("真机验收截图轮播 · 非连续录屏 · 不表示推理速度",x:28,top:757,size:16,color:NSColor(calibratedWhite:0.45,alpha:1))
        NSGraphicsContext.restoreGraphicsState()
        let image = ctx.makeImage()!
        CGImageDestinationAddImage(dest,image,[kCGImagePropertyGIFDictionary:[kCGImagePropertyGIFDelayTime:4.5]] as CFDictionary)
        let preview = CGImageDestinationCreateWithURL(output.appendingPathComponent("\(demo.name)-\(index).png") as CFURL,UTType.png.identifier as CFString,1,nil)!
        CGImageDestinationAddImage(preview,image,nil); precondition(CGImageDestinationFinalize(preview))
    }
    precondition(CGImageDestinationFinalize(dest))
    let verify = CGImageSourceCreateWithURL(gif as CFURL,nil)!
    precondition(CGImageSourceGetCount(verify)==demo.frames.count)
    for index in 0..<demo.frames.count { precondition(CGImageSourceCreateImageAtIndex(verify,index,nil) != nil) }
    print("\(demo.name): \(demo.frames.count) frames, \(try Data(contentsOf:gif).count) bytes")
}
