import SwiftUI
import AVFoundation

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
                let matcher = data.state.parameters?.matcher ?? model.defaultMatcher ?? SimulatedFaceMatcher()
                let portrait = data.state.parameters!.referencePortrait
                let faceMatcherSession = data.state.parameters?.faceMatcherSession ?? matcher.createSession(referencePortrait: portrait)
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

private struct FaceMatcherPromptView: View {
    let title: String
    let subtitle: String
    let faceMatcherSession: FaceMatcherSession
    let onSuccess: () -> Void
    let onCancel: () -> Void

    @State private var promptState: FaceMatcherPromptState? = nil

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Spacer()
                Button {
                    faceMatcherSession.cancel()
                    onCancel()
                } label: {
                    Image(systemName: "xmark")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }

            Text(promptState?.messageAbove ?? title)
                .font(.title2)
                .fontWeight(.bold)
                .multilineTextAlignment(.center)

            Spacer()

            ZStack {
                CameraPreview(onFrame: { frame, completion in
                    guard promptState?.outcome != FaceMatcherPromptState.Outcome.success else {
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

                if promptState?.outcome == FaceMatcherPromptState.Outcome.success {
                    Circle()
                        .fill(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .frame(width: 64, height: 64)
                        .overlay(
                            Image(systemName: "checkmark")
                                .font(.system(size: 32, weight: .bold))
                                .foregroundColor(.white)
                        )
                }

                let segments = promptState?.ringSegments ?? []
                SegmentedRingView(segments: segments)
                    .frame(width: 232, height: 296)
            }
            .frame(width: 232, height: 296)

            let belowText = promptState?.messageBelow ?? subtitle
            if !belowText.isEmpty {
                Text(belowText)
                    .font(.body)
                    .foregroundColor(promptState?.outcome == FaceMatcherPromptState.Outcome.failed ? .red : .secondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            Button("Cancel") {
                faceMatcherSession.cancel()
                onCancel()
            }
            .padding(.bottom, 16)
        }
        .padding(.horizontal, 24)
        .task {
            promptState = faceMatcherSession.state.value
            startSimulationTimerIfNeeded()
            for await state in faceMatcherSession.state {
                self.promptState = state
                if state.outcome == FaceMatcherPromptState.Outcome.success {
                    try? await Task.sleep(nanoseconds: 1_200_000_000)
                    onSuccess()
                }
            }
        }
    }

    private func startSimulationTimerIfNeeded() {
        #if targetEnvironment(simulator)
        Task {
            while promptState?.outcome == nil || promptState?.outcome == FaceMatcherPromptState.Outcome.inProgress {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard promptState?.outcome == nil || promptState?.outcome == FaceMatcherPromptState.Outcome.inProgress else { break }
                let frame = CameraFrame(
                    width: 640,
                    height: 480,
                    rotationDegrees: 0,
                    pixelFormat: PixelFormat.unknown,
                    data: Data().toByteString(),
                    platformHandle: nil
                )
                _ = try? await faceMatcherSession.feedFrame(frame: frame)
            }
        }
        #endif
    }
}

private func createRoundedRectPath(rect: CGRect, cornerRadius: CGFloat) -> SwiftUI.Path {
    var path = SwiftUI.Path()
    let r = min(cornerRadius, min(rect.width, rect.height) / 2.0)
    let centerX = rect.midX
    let left = rect.minX
    let top = rect.minY
    let right = rect.maxX
    let bottom = rect.maxY

    path.move(to: CGPoint(x: centerX, y: top))
    path.addLine(to: CGPoint(x: right - r, y: top))
    path.addArc(
        center: CGPoint(x: right - r, y: top + r),
        radius: r,
        startAngle: Angle(degrees: -90),
        endAngle: Angle(degrees: 0),
        clockwise: false
    )
    path.addLine(to: CGPoint(x: right, y: bottom - r))
    path.addArc(
        center: CGPoint(x: right - r, y: bottom - r),
        radius: r,
        startAngle: Angle(degrees: 0),
        endAngle: Angle(degrees: 90),
        clockwise: false
    )
    path.addLine(to: CGPoint(x: left + r, y: bottom))
    path.addArc(
        center: CGPoint(x: left + r, y: bottom - r),
        radius: r,
        startAngle: Angle(degrees: 90),
        endAngle: Angle(degrees: 180),
        clockwise: false
    )
    path.addLine(to: CGPoint(x: left, y: top + r))
    path.addArc(
        center: CGPoint(x: left + r, y: top + r),
        radius: r,
        startAngle: Angle(degrees: 180),
        endAngle: Angle(degrees: 270),
        clockwise: false
    )
    path.closeSubpath()
    return path
}

private struct SegmentedRingView: View {
    let segments: [RingSegment]

    var body: some View {
        Canvas { context, size in
            let numSegments = 18
            let baseStrokeWidth: CGFloat = 5.0
            let blackBorderWidth: CGFloat = 10.0
            let pad: CGFloat = 6.0
            let cornerRadius: CGFloat = 36.0
            let rect = CGRect(x: pad, y: pad, width: size.width - pad * 2, height: size.height - pad * 2)
            let fullPath = createRoundedRectPath(rect: rect, cornerRadius: cornerRadius)

            // 1. Solid black border around the vertical rounded rectangle
            context.stroke(
                fullPath,
                with: .color(Color.black),
                style: StrokeStyle(lineWidth: blackBorderWidth, lineCap: .round)
            )

            // 2. Draw 18 segments on top of the black border
            let count = min(numSegments, segments.count)
            let segFrac: CGFloat = (1.0 / CGFloat(numSegments)) * 0.72

            for i in 0..<count {
                let segment = segments[i]
                let centerFrac = CGFloat(i) / CGFloat(numSegments)
                let rawStart = centerFrac - segFrac / 2.0
                let rawEnd = centerFrac + segFrac / 2.0

                var segPath = SwiftUI.Path()
                if rawStart < 0.0 {
                    segPath.addPath(fullPath.trimmedPath(from: 1.0 + rawStart, to: 1.0))
                    segPath.addPath(fullPath.trimmedPath(from: 0.0, to: rawEnd))
                } else if rawEnd > 1.0 {
                    segPath.addPath(fullPath.trimmedPath(from: rawStart, to: 1.0))
                    segPath.addPath(fullPath.trimmedPath(from: 0.0, to: rawEnd - 1.0))
                } else {
                    segPath = fullPath.trimmedPath(from: rawStart, to: rawEnd)
                }

                let strokeWidth = baseStrokeWidth * CGFloat(segment.scale)
                let color = Color(
                    red: Double(segment.color.red),
                    green: Double(segment.color.green),
                    blue: Double(segment.color.blue),
                    opacity: Double(segment.color.alpha)
                )
                context.stroke(
                    segPath,
                    with: .color(color),
                    style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round)
                )
            }
        }
    }
}

private struct CameraPreview: UIViewControllerRepresentable {
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

private class CameraViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFrame: ((CameraFrame, @escaping () -> Void) -> Void)?

    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let frameQueue = DispatchQueue(label: "org.multipaz.camera_frame_queue")
    private var previewLayer: AVCaptureVideoPreviewLayer?

    private var isProcessing = false
    private var lastProcessedTime: TimeInterval = 0

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkPermissionAndSetupCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
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

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.videoGravity = .resizeAspectFill
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

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let width = Int32(CVPixelBufferGetWidth(imageBuffer))
        let height = Int32(CVPixelBufferGetHeight(imageBuffer))

        isProcessing = true
        lastProcessedTime = now

        // We intentionally do not retain sampleBuffer or imageBuffer across async tasks
        // to avoid starving AVCaptureVideoDataOutput's buffer pool.
        let frame = CameraFrame(
            width: width,
            height: height,
            rotationDegrees: 0,
            pixelFormat: PixelFormat.unknown,
            data: Data().toByteString(),
            platformHandle: nil
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
