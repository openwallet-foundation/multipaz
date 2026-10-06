import SwiftUI
import AVFoundation
import CoreImage

private struct FaceMatcherPromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean>
}

struct FaceMatcherPromptDialog: View {
    let model: FaceMatcherPromptDialogModel
    let toHumanReadable: @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable

    @State private var data: FaceMatcherPromptDialogData? = nil
    @State private var humanReadableReason: ReasonHumanReadable? = nil

    init(
        model: FaceMatcherPromptDialogModel,
        toHumanReadable: @escaping @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable
    ) {
        self.model = model
        self.toHumanReadable = toHumanReadable
    }

    var body: some View {
        VStack {}
            .task {
                for await state in model.dialogState {
                    if state is PromptDialogModelNoDialogState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean> {
                        data = nil
                    } else if state is PromptDialogModelDialogShownState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean> {
                        data = FaceMatcherPromptDialogData(
                            state: state as! PromptDialogModelDialogShownState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean>
                        )
                    }
                }
            }
            .onChange(of: data) { oldValue, newValue in
                if newValue == nil {
                    oldValue?.state.resultChannel.close(cause: PromptDismissedException())
                }
            }
            .task(id: data?.id) {
                if let data {
                    humanReadableReason = await toHumanReadable(
                        data.state.parameters!.reason,
                        nil
                    )
                } else {
                    humanReadableReason = nil
                }
            }
            .sheet(item: $data) { data in
                let faceMatcherSession = data.state.parameters!.faceMatcherSession
                FaceMatcherPromptView(
                    title: humanReadableReason?.title ?? "Verify it's you",
                    subtitle: humanReadableReason?.subtitle ?? "Look at the camera to verify your identity",
                    faceMatcherSession: faceMatcherSession,
                    onSuccess: {
                        Task {
                            try await data.state.resultChannel.send(element: KotlinBoolean(value: true))
                            self.data = nil
                        }
                    },
                    onCancel: {
                        faceMatcherSession.cancel()
                        data.state.resultChannel.close(cause: PromptDismissedException())
                        self.data = nil
                    }
                )
                .presentationDragIndicator(.hidden)
            }
    }
}

private struct FaceMatcherSheetHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct FaceMatcherPromptView: View {
    let title: String
    let subtitle: String
    let faceMatcherSession: FaceMatcherSession
    let onSuccess: () -> Void
    let onCancel: () -> Void

    @State private var promptState: FaceMatcherPromptState? = nil
    @State private var contentHeight: CGFloat = 460

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                ZStack {
                    Text(title)
                        .font(.title2)
                        .fontWeight(.bold)
                        .frame(maxWidth: .infinity, alignment: .center)

                    HStack {
                        Spacer()
                        Button {
                            faceMatcherSession.cancel()
                            onCancel()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(.secondary)
                                .padding(8)
                        }
                    }
                }

                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.top, 16)

            ZStack {
                CameraPreview(onFrame: { frame, completion in
                    guard promptState?.status != FaceMatcherPromptState.Status.success else {
                        completion()
                        return
                    }
                    Task {
                        _ = try? await faceMatcherSession.feedFrame(frame: frame)
                        completion()
                    }
                })
                .frame(width: 220, height: 284)
                .clipShape(RoundedRectangle(cornerRadius: 36))

                if let overlay = promptState?.overlay {
                    FaceMatcherOverlayView(overlay: overlay)
                        .frame(width: 220, height: 284)
                        .clipShape(RoundedRectangle(cornerRadius: 36))
                }

                if promptState?.status == FaceMatcherPromptState.Status.success {
                    Circle()
                        .fill(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .frame(width: 64, height: 64)
                        .overlay(
                            Image(systemName: "checkmark")
                                .font(.system(size: 32, weight: .bold))
                                .foregroundColor(.white)
                        )
            }
            .frame(width: 220, height: 284)
            .contentShape(RoundedRectangle(cornerRadius: 36))
            .onTapGesture(coordinateSpace: .local) { location in
                let normX = Float(max(0.0, min(1.0, location.x / 220.0)))
                let normY = Float(max(0.0, min(1.0, location.y / 284.0)))
                faceMatcherSession.onTouchEvent(x: normX, y: normY)
            }

            let messageText = promptState?.message ?? ""
            Text(messageText)
                .font(.body)
                .foregroundColor(promptState?.status == FaceMatcherPromptState.Status.failed ? .red : .secondary)
                .multilineTextAlignment(.center)
                .frame(minHeight: 24)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
        .background(
            GeometryReader { proxy in
                Color.clear
                    .preference(key: FaceMatcherSheetHeightKey.self, value: proxy.size.height)
            }
        )
        .onPreferenceChange(FaceMatcherSheetHeightKey.self) { newHeight in
            if newHeight > 100 && abs(newHeight - contentHeight) > 2 {
                DispatchQueue.main.async {
                    contentHeight = newHeight
                }
            }
        }
        .presentationDetents([.height(contentHeight)])
        .task {
            promptState = faceMatcherSession.state.value
            startSimulationTimerIfNeeded()
            for await state in faceMatcherSession.state {
                self.promptState = state
                if state.status == FaceMatcherPromptState.Status.success {
                    try? await Task.sleep(nanoseconds: 1_200_000_000)
                    onSuccess()
                }
            }
        }
    }

    private func startSimulationTimerIfNeeded() {
        #if targetEnvironment(simulator)
        Task {
            while promptState?.status == nil || promptState?.status == FaceMatcherPromptState.Status.inProgress {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard promptState?.status == nil || promptState?.status == FaceMatcherPromptState.Status.inProgress else { break }
                let frame = CameraFrame(
                    width: 640,
                    height: 480,
                    rotationDegrees: 0,
                    platformHandle: nil
                )
                _ = try? await faceMatcherSession.feedFrame(frame: frame)
            }
        }
        #endif
    }
}

struct FaceMatcherOverlayView: View {
    let overlay: OverlayFrame

    var body: some View {
        if let image = overlay.platformHandle as? UIImage {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .allowsHitTesting(false)
        }
    }
}


struct CameraPreview: UIViewControllerRepresentable {
    let onFrame: (CameraFrame, @escaping () -> Void) -> Void

    func makeUIViewController(context: Context) -> CameraViewController {
        let vc = CameraViewController()
        vc.onFrame = onFrame
        return vc
    }

    func updateUIViewController(_ uiViewController: CameraViewController, context: Context) {
        uiViewController.onFrame = onFrame
    }
}

class CameraViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFrame: ((CameraFrame, @escaping () -> Void) -> Void)?

    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let frameQueue = DispatchQueue(label: "org.multipaz.camera_frame_queue")
    private var previewLayer: AVCaptureVideoPreviewLayer?

    private var isProcessing = false
    private var lastProcessedTime: TimeInterval = 0
    private let ciContext = CIContext()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkPermissionAndSetupCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        if let previewConnection = previewLayer?.connection, previewConnection.isVideoOrientationSupported {
            previewConnection.videoOrientation = .portrait
        }
    }

    private func checkPermissionAndSetupCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    DispatchQueue.main.async {
                        self?.setupCamera()
                    }
                }
            }
        default:
            break
        }
    }

    private func setupCamera() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device) else {
            return
        }

        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }

        if captureSession.canSetSessionPreset(.hd1920x1080) {
            captureSession.sessionPreset = .hd1920x1080
        } else if captureSession.canSetSessionPreset(.high) {
            captureSession.sessionPreset = .high
        }

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        if let connection = videoOutput.connection(with: .video) {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
            if connection.isVideoMirroringSupported {
                connection.isVideoMirrored = false
            }
        }

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.videoGravity = .resizeAspectFill
        if let previewConnection = preview.connection {
            if previewConnection.isVideoOrientationSupported {
                previewConnection.videoOrientation = .portrait
            }
            if previewConnection.isVideoMirroringSupported {
                previewConnection.automaticallyAdjustsVideoMirroring = false
                previewConnection.isVideoMirrored = true
            }
        }
        view.layer.addSublayer(preview)
        self.previewLayer = preview

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.stopRunning()
        }
    }

    deinit {
        if captureSession.isRunning {
            captureSession.stopRunning()
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = CACurrentMediaTime()
        // Throttle evaluation to at most 4 times per second (250ms), and only if previous frame has completed
        guard !isProcessing, (now - lastProcessedTime) >= 0.25 else {
            return
        }

        autoreleasepool {
            guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            let width = Int32(CVPixelBufferGetWidth(imageBuffer))
            let height = Int32(CVPixelBufferGetHeight(imageBuffer))

            isProcessing = true
            lastProcessedTime = now

            let ciImage = CIImage(cvPixelBuffer: imageBuffer)
            guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else {
                isProcessing = false
                return
            }
            let uiImage = UIImage(cgImage: cgImage)

            // Pass an independent UIImage as platformHandle so the CVPixelBuffer is released immediately.
            let frame = CameraFrame(
                width: width,
                height: height,
                rotationDegrees: 0,
                platformHandle: uiImage
            )

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if let onFrame = self.onFrame {
                    onFrame(frame) { [weak self] in
                        self?.frameQueue.async {
                            self?.isProcessing = false
                        }
                    }
                } else {
                    self.frameQueue.async {
                        self.isProcessing = false
                    }
                }
            }
        }
    }
}
