#if os(iOS)
import SwiftUI
import AVFoundation
import UIKit

/// The camera, full screen: a plain camera that opens at once, with the Camera app's zoom steps,
/// pinch to zoom and tap to focus. Nothing is recognized live — review reads the slide from the
/// photo after the shutter, finding the screen in it first (`SlideScreen`). Falls back to the
/// standard camera where there's no back camera to run.
struct SlideCameraView: View {
    let onCapture: (CGImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var camera = SlideCamera()
    @State private var capturing = false

    private var hasBackCamera: Bool { AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil }

    var body: some View {
        Group {
            switch access {
            case .authorized:
                if hasBackCamera { liveCamera } else { ImagePickerCamera(onCapture: onCapture, onCancel: { dismiss() }) }
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

    private var liveCamera: some View {
        CameraPreview(camera: camera)
            .ignoresSafeArea()
            .overlay(alignment: .top) {
                Text("Point at the slide, then take the photo.")
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
                        .disabled(capturing || camera.lenses == nil)
                        .accessibilityLabel("Take photo of slide")
                        Spacer()
                        Color.clear.frame(width: 80, height: 1)
                    }
                    .padding(.horizontal, 24)
                }
                .padding(.bottom, 40)
            }
            .task { await camera.start() }
            .onDisappear { camera.stop() }
    }

    /// The Camera app's zoom steps for this iPhone; the one in use shows the exact zoom.
    @ViewBuilder private var zoomControl: some View {
        if let steps = camera.lenses?.steps, steps.count > 1 {
            let zoom = camera.zoom
            let current = steps.last { zoom >= $0 - 0.05 } ?? steps[0]
            HStack(spacing: 6) {
                ForEach(steps, id: \.self) { step in
                    let active = step == current
                    Button { camera.setZoom(step) } label: {
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

    /// "2×", "0.5×", "2.4×".
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
        guard !capturing, camera.lenses != nil else { return }
        capturing = true
        Task {
            defer { capturing = false }
            if let image = await camera.capture() { onCapture(image) }
        }
    }
}

/// The camera's state for the view: its lenses once it's running, the zoom, and the rotation of
/// the phone for an upright photo.
@Observable
final class SlideCamera {
    let session = SlideCameraSession()
    private(set) var lenses: SlideCameraSession.Lenses?
    /// The zoom as the Camera app shows it: 1× is the main camera.
    private(set) var zoom = 1.0
    @ObservationIgnored weak var preview: CameraPreviewView?
    @ObservationIgnored private var pinchStart = 1.0

    /// Starts the camera, or starts it again after it was stopped.
    func start() async {
        guard let started = await session.start() else { return }
        lenses = started
        if let device = session.device { preview?.attach(device) }
    }

    func stop() { session.stop() }

    func setZoom(_ factor: Double) {
        guard let lenses else { return }
        zoom = min(max(factor, lenses.minZoom), lenses.maxZoom)
        session.setZoom(zoom / lenses.multiplier)
    }

    func pinchBegan() { pinchStart = zoom }
    func pinched(_ scale: Double) { setZoom(pinchStart * scale) }

    /// Focuses and exposes for a point in the camera's own coordinates (0…1).
    func focus(at point: CGPoint) { session.focus(at: point) }

    func capture() async -> CGImage? {
        // Upright as the phone is held, whatever the screen's orientation.
        let angle = preview?.rotation?.videoRotationAngleForHorizonLevelCapture ?? 90
        guard let data = await session.capture(rotationAngle: angle) else { return nil }
        return await Task.detached(priority: .userInitiated) { SlideImage.decode(data) }.value
    }
}

/// The capture session, on its own queue: starting a camera takes long enough to stall the screen.
nonisolated final class SlideCameraSession: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    /// The back camera's zoom, as the Camera app shows it (1× is the main camera): the range and the
    /// steps to offer, and what to multiply the device's own zoom factor by to get there.
    struct Lenses: Sendable {
        var multiplier: Double
        var minZoom: Double
        var maxZoom: Double
        var steps: [Double]
    }

    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "com.blainemiller.ScriptureAlone.SlideCamera")
    private(set) var device: AVCaptureDevice?
    private var photo: CheckedContinuation<Data?, Never>?

    /// Sets up the back camera — all its lenses, so 0.5× and the telephoto switch as the Camera app
    /// does — and starts it. Returns as soon as the zoom is known; the preview follows a moment later.
    func start() async -> Lenses? {
        await withCheckedContinuation { continuation in
            queue.async {
                let lenses = self.configure()
                continuation.resume(returning: lenses)
                if lenses != nil, !self.session.isRunning { self.session.startRunning() }
            }
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func configure() -> Lenses? {
        if let device { return zoomRange(of: device) }
        let camera = AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        guard let camera, let input = try? AVCaptureDeviceInput(device: camera) else { return nil }
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard session.canAddInput(input), session.canAddOutput(photoOutput) else {
            session.commitConfiguration()
            return nil
        }
        session.addInput(input)
        session.addOutput(photoOutput)
        session.commitConfiguration()

        let lenses = zoomRange(of: camera)
        if (try? camera.lockForConfiguration()) != nil {
            // Open at 1× — the main camera — not the ultra wide a triple camera starts on.
            let main = min(max(1 / lenses.multiplier, Double(camera.minAvailableVideoZoomFactor)), Double(camera.maxAvailableVideoZoomFactor))
            camera.videoZoomFactor = CGFloat(main)
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
            camera.unlockForConfiguration()
        }
        device = camera
        return lenses
    }

    /// 0.5× with an ultra wide; 1× and 2×; the telephoto's zoom, where the camera switches to it;
    /// and twice that where the telephoto is 48 MP (8× beside 4×).
    private func zoomRange(of camera: AVCaptureDevice) -> Lenses {
        let multiplier = Double(camera.displayVideoZoomFactorMultiplier)
        let minZoom = Double(camera.minAvailableVideoZoomFactor) * multiplier
        let constituents = camera.isVirtualDevice ? camera.constituentDevices : [camera]
        let telephoto = constituents.first { $0.deviceType == .builtInTelephotoCamera }
        var steps: Set<Double> = [1, 2]
        if minZoom <= 0.51 { steps.insert(0.5) }
        // Past about 3× the telephoto (or 10× without one), it's only blur.
        var reach = 10.0
        if let telephoto, let switchOver = camera.virtualDeviceSwitchOverVideoZoomFactors.last {
            let zoom = (switchOver.doubleValue * multiplier * 10).rounded() / 10
            steps.insert(zoom)
            let pixels = telephoto.formats.flatMap(\.supportedMaxPhotoDimensions).map { Int($0.width) * Int($0.height) }.max() ?? 0
            if pixels >= 40_000_000 { steps.insert(zoom * 2) }
            reach = zoom * 3
        }
        let maxZoom = min(Double(camera.maxAvailableVideoZoomFactor) * multiplier, reach)
        return Lenses(multiplier: multiplier, minZoom: minZoom, maxZoom: maxZoom,
                      steps: steps.filter { $0 >= minZoom - 0.01 && $0 <= maxZoom + 0.01 }.sorted())
    }

    /// Sets the device's own zoom factor.
    func setZoom(_ factor: Double) {
        queue.async {
            guard let device = self.device, (try? device.lockForConfiguration()) != nil else { return }
            device.videoZoomFactor = CGFloat(min(max(factor, Double(device.minAvailableVideoZoomFactor)), Double(device.maxAvailableVideoZoomFactor)))
            device.unlockForConfiguration()
        }
    }

    func focus(at point: CGPoint) {
        queue.async {
            guard let device = self.device, (try? device.lockForConfiguration()) != nil else { return }
            if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusPointOfInterest = point
                device.focusMode = .continuousAutoFocus
            }
            if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposurePointOfInterest = point
                device.exposureMode = .continuousAutoExposure
            }
            device.unlockForConfiguration()
        }
    }

    /// Takes the photo, turned by `rotationAngle`; its file data (HEIC or JPEG) or nil.
    func capture(rotationAngle: CGFloat) async -> Data? {
        await withCheckedContinuation { continuation in
            queue.async {
                guard self.photo == nil, self.session.isRunning else {
                    continuation.resume(returning: nil)
                    return
                }
                self.photo = continuation
                if let connection = self.photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(rotationAngle) {
                    connection.videoRotationAngle = rotationAngle
                }
                self.photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: (any Error)?) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        queue.async {
            self.photo?.resume(returning: data)
            self.photo = nil
        }
    }
}

/// The live picture, with pinch to zoom and tap to focus.
final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    /// Which way up the phone is, for the preview and the photo.
    private(set) var rotation: AVCaptureDevice.RotationCoordinator?
    weak var camera: SlideCamera?

    init(camera: SlideCamera) {
        self.camera = camera
        super.init(frame: .zero)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.session = camera.session.session
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:))))
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func attach(_ device: AVCaptureDevice) {
        rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if let rotation, let connection = previewLayer.connection,
           connection.isVideoRotationAngleSupported(rotation.videoRotationAngleForHorizonLevelPreview) {
            connection.videoRotationAngle = rotation.videoRotationAngleForHorizonLevelPreview
        }
    }

    @objc private func pinched(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began: camera?.pinchBegan()
        case .changed: camera?.pinched(Double(gesture.scale))
        default: break
        }
    }

    @objc private func tapped(_ gesture: UITapGestureRecognizer) {
        camera?.focus(at: previewLayer.captureDevicePointConverted(fromLayerPoint: gesture.location(in: self)))
    }
}

private struct CameraPreview: UIViewRepresentable {
    let camera: SlideCamera

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView(camera: camera)
        camera.preview = view
        if let device = camera.session.device { view.attach(device) }
        return view
    }

    func updateUIView(_ view: CameraPreviewView, context: Context) {}
}

/// The system camera, for devices without a back camera to run.
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
