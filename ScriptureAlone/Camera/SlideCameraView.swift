#if os(iOS)
import SwiftUI
import AVFoundation
import VisionKit
import UIKit

/// The camera, full screen. Uses VisionKit's live scanner — text on the slide lights up as
/// it's recognized, and tapping any of it (or the shutter) takes the picture — and falls back
/// to the standard camera on devices without it.
struct SlideCameraView: View {
    let onCapture: (CGImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var scanner = ScannerHandle()
    @State private var capturing = false

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
                    .disabled(capturing)
                    .accessibilityLabel("Take photo of slide")
                    Spacer()
                    Color.clear.frame(width: 80, height: 1)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 40)
            }
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
        guard !capturing, let controller = scanner.controller else { return }
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
