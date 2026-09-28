#if os(iOS)
import SwiftUI
import AVFoundation
import VisionKit
import UIKit

/// The camera, full screen. Uses VisionKit's live scanner — text on the slide lights up as
/// it's recognized, and tapping any of it (or the shutter) takes the picture — and falls back
/// to the standard camera on devices without it. Zoom steps and pinching bring a far-off screen
/// close; review then finds the screen in the photo and reads just that (`SlideScreen`).
struct SlideCameraView: View {
    let onCapture: (CGImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var scanner = ScannerHandle()
    @State private var capturing = false
    @State private var zoom = 1.0
    /// How far the camera zooms; 0 until it says (it can take seconds to after the preview shows).
    @State private var maxZoom = 0.0
    /// A zoom step tapped before the camera could go that far, put through once it can.
    @State private var wantedZoom: Double?
    /// The scanner is running: until then zooming does nothing and a photo is an unfocused frame.
    @State private var scanning = false

    private static let zoomSteps: [Double] = [1, 2, 3, 5]

    private var useLiveScanner: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    var body: some View {
        Group {
            switch access {
            case .authorized:
                if useLiveScanner { liveScanner } else { ImagePickerCamera(onCapture: onCapture, onCancel: { dismiss() }) }
            case .notDetermined:
                Color.black.task {
                    _ = await AVCaptureDevice.requestAccess(for: .video)
                    access = AVCaptureDevice.authorizationStatus(for: .video)
                }
            default:
                denied
            }
        }
        .ignoresSafeArea()
    }

    private var liveScanner: some View {
        DataScannerRepresentable(handle: scanner, onTapText: capture)
            .ignoresSafeArea()
            .overlay(alignment: .top) {
                Text("Point at the slide. Tap any highlighted text or the shutter.")
                    .font(.callout.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .glassEffect()
                    .padding(.top, 60)
            }
            .overlay(alignment: .bottom) {
                VStack(spacing: 18) {
                    zoomControl
                    HStack {
                        Button("Cancel") { dismiss() }
                            .buttonStyle(.glass)
                        Spacer()
                        Button(action: capture) {
                            ZStack {
                                Circle().strokeBorder(.white, lineWidth: 4).frame(width: 76, height: 76)
                                Circle().fill(.white).frame(width: 62, height: 62)
                                if capturing { ProgressView().tint(.black) }
                            }
                        }
                        .disabled(capturing || !scanning)
                        .accessibilityLabel("Take photo of slide")
                        Spacer()
                        Color.clear.frame(width: 80, height: 1)
                    }
                    .padding(.horizontal, 24)
                }
                .padding(.bottom, 40)
            }
            .task {
                // Starting the scanner before it's on screen can fail without a word — the first time
                // the camera opens, the permission prompt happens to wait long enough; after that it
                // doesn't — so keep at it until it runs. Then follow pinches, which change the
                // scanner's zoom without telling anyone.
                var lastStart = ContinuousClock.now - .seconds(1)
                while !Task.isCancelled {
                    if let controller = scanner.controller {
                        // Not every 100 ms: each attempt while the camera is still starting can set it back.
                        if !controller.isScanning, ContinuousClock.now - lastStart > .milliseconds(500) {
                            lastStart = .now
                            try? controller.startScanning()
                        }
                        if scanning != controller.isScanning { scanning = controller.isScanning }
                        let reach = controller.maxZoomFactor > 1.01 ? controller.maxZoomFactor : 0
                        if abs(reach - maxZoom) > 0.01 { maxZoom = reach }
                        if let wanted = wantedZoom {
                            // Once the camera says how far it goes, as close as it gets.
                            if controller.isScanning, controller.maxZoomFactor >= wanted - 0.01 || reach > 0 {
                                controller.zoomFactor = max(min(wanted, controller.maxZoomFactor), controller.minZoomFactor)
                                zoom = controller.zoomFactor
                                wantedZoom = nil
                            }
                        } else if abs(controller.zoomFactor - zoom) > 0.01 {
                            zoom = controller.zoomFactor
                        }
                    }
                    try? await Task.sleep(for: .milliseconds(100))
                }
            }
    }

    /// The camera app's zoom steps, as far as the camera goes; the one in use shows the exact zoom.
    @ViewBuilder private var zoomControl: some View {
        // Shown straight away; once the camera says how far it goes, only the steps it reaches.
        let steps = maxZoom > 0 ? Self.zoomSteps.filter { $0 <= maxZoom + 0.01 } : Self.zoomSteps
        if steps.count > 1 {
            let current = steps.last { zoom >= $0 - 0.05 } ?? steps[0]
            HStack(spacing: 6) {
                ForEach(steps, id: \.self) { step in
                    let active = step == current
                    Button { setZoom(step) } label: {
                        Text(verbatim: Self.zoomLabel(active ? zoom : step))
                            .font(active ? Font.footnote.weight(.semibold) : Font.caption.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(active ? Color.yellow : Color.white)
                            .frame(width: active ? 42 : 34, height: active ? 42 : 34)
                            .background(.black.opacity(0.45), in: .circle)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Zoom \(Self.zoomLabel(step))"))
                    .accessibilityAddTraits(active ? .isSelected : [])
                }
            }
            .padding(4)
            .background(.black.opacity(0.3), in: .capsule)
        }
    }

    private func setZoom(_ factor: Double) {
        zoom = factor
        guard let controller = scanner.controller, controller.isScanning, controller.maxZoomFactor >= factor - 0.01 else {
            // Not ready to go that far yet: the loop above puts it through when it is.
            wantedZoom = factor
            return
        }
        wantedZoom = nil
        controller.zoomFactor = max(factor, controller.minZoomFactor)
    }

    /// "2×", "2.4×".
    private static func zoomLabel(_ factor: Double) -> String {
        factor.formatted(.number.precision(.fractionLength(0...1))) + "×"
    }

    private var denied: some View {
        ContentUnavailableView {
            Label("Camera Access Is Off", systemImage: "camera.fill")
        } description: {
            Text("Scripture Alone reads slides on your iPhone and never uploads them. Turn on camera access in Settings, or choose a photo you’ve already taken.")
        } actions: {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent)
            Button("Cancel") { dismiss() }
        }
        .background(Color(uiColor: .systemBackground))
    }

    private func capture() {
        guard !capturing, let controller = scanner.controller, controller.isScanning else { return }
        capturing = true
        Task {
            defer { capturing = false }
            if let photo = try? await controller.capturePhoto(), let image = photo.uprightCGImage() {
                onCapture(image)
            }
        }
    }
}

@Observable
final class ScannerHandle {
    weak var controller: DataScannerViewController?
}

private struct DataScannerRepresentable: UIViewControllerRepresentable {
    let handle: ScannerHandle
    let onTapText: () -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes: [.text()], qualityLevel: .accurate,
                                                   recognizesMultipleItems: true, isHighFrameRateTrackingEnabled: false,
                                                   isPinchToZoomEnabled: true, isGuidanceEnabled: false,
                                                   isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        handle.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        if !controller.isScanning { try? controller.startScanning() }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onTapText: onTapText) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onTapText: () -> Void
        init(onTapText: @escaping () -> Void) { self.onTapText = onTapText }

        func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) { onTapText() }
    }
}

/// The system camera, for devices without the live text scanner.
private struct ImagePickerCamera: UIViewControllerRepresentable {
    let onCapture: (CGImage) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onCapture: onCapture, onCancel: onCancel) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onCapture: (CGImage) -> Void
        let onCancel: () -> Void
        init(onCapture: @escaping (CGImage) -> Void, onCancel: @escaping () -> Void) {
            self.onCapture = onCapture
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = (info[.originalImage] as? UIImage)?.uprightCGImage() { onCapture(image) } else { onCancel() }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onCancel() }
    }
}

extension UIImage {
    /// Bakes in the orientation and caps the size, so Vision and the thumbnail see the slide upright.
    func uprightCGImage(maxPixelSize: CGFloat = 3000) -> CGImage? {
        let pixels = CGSize(width: size.width * scale, height: size.height * scale)
        let factor = min(1, maxPixelSize / max(pixels.width, pixels.height))
        let target = CGSize(width: (pixels.width * factor).rounded(), height: (pixels.height * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }.cgImage
    }
}
#endif
