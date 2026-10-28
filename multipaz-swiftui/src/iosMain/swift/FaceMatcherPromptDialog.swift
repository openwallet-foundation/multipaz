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
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    if state is PromptDialogModelNoDialogState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean> {
                        withTransaction(transaction) {
                            data = nil
                        }
                    } else if state is PromptDialogModelDialogShownState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean> {
                        withTransaction(transaction) {
                            data = FaceMatcherPromptDialogData(
                                state: state as! PromptDialogModelDialogShownState<FaceMatcherPromptDialogModel.FaceMatcherRequest, KotlinBoolean>
                            )
                        }
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
                    let parameters = data.state.parameters!
                    if parameters.usesCustomUserInterface {
                        let faceMatcherSession = parameters.faceMatcherSession
                        for await state in faceMatcherSession.state {
                            if state.status == FaceMatcherPromptState.Status.success {
                                faceMatcherSession.cancel()
                                try? await data.state.resultChannel.send(element: KotlinBoolean(value: true))
                                var transaction = Transaction()
                                transaction.disablesAnimations = true
                                withTransaction(transaction) {
                                    self.data = nil
                                }
                                break
                            } else if state.status == FaceMatcherPromptState.Status.failed {
                                faceMatcherSession.cancel()
                                data.state.resultChannel.close(cause: PromptDismissedException())
                                var transaction = Transaction()
                                transaction.disablesAnimations = true
                                withTransaction(transaction) {
                                    self.data = nil
                                }
                                break
                            }
                        }
                    } else {
                        humanReadableReason = await toHumanReadable(
                            parameters.reason,
                            nil
                        )
                    }
                } else {
                    humanReadableReason = nil
                }
            }
            .fullScreenCover(
                item: Binding(
                    get: {
                        if let data, !data.state.parameters!.usesCustomUserInterface {
                            return data
                        }
                        return nil
                    },
                    set: { data = $0 }
                )
            ) { data in
                let faceMatcherSession = data.state.parameters!.faceMatcherSession
                let requireConfirmation = data.state.parameters!.requireConfirmation
                    || (humanReadableReason?.requireConfirmation == true)
                FaceMatcherPromptView(
                    title: humanReadableReason?.title ?? "Verify it's you",
                    subtitle: humanReadableReason?.subtitle ?? "Look at the camera to verify your identity",
                    requireConfirmation: requireConfirmation,
                    faceMatcherSession: faceMatcherSession,
                    onSuccess: {
                        faceMatcherSession.cancel()
                        Task {
                            try await data.state.resultChannel.send(element: KotlinBoolean(value: true))
                            var transaction = Transaction()
                            transaction.disablesAnimations = true
                            withTransaction(transaction) {
                                self.data = nil
                            }
                        }
                    },
                    onCancel: {
                        faceMatcherSession.cancel()
                        data.state.resultChannel.close(cause: PromptDismissedException())
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) {
                            self.data = nil
                        }
                    }
                )
                .presentationBackground(.clear)
            }
    }
}

private struct FaceMatcherLivenessPromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, NSDictionary>
}

struct FaceMatcherLivenessPromptDialog: View {
    let model: FaceMatcherLivenessPromptDialogModel
    let toHumanReadable: @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable

    @State private var data: FaceMatcherLivenessPromptDialogData? = nil
    @State private var humanReadableReason: ReasonHumanReadable? = nil

    init(
        model: FaceMatcherLivenessPromptDialogModel,
        toHumanReadable: @escaping @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable
    ) {
        self.model = model
        self.toHumanReadable = toHumanReadable
    }

    var body: some View {
        VStack {}
            .task {
                for await state in model.dialogState {
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    if state is PromptDialogModelNoDialogState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, NSDictionary> {
                        withTransaction(transaction) {
                            data = nil
                        }
                    } else if state is PromptDialogModelDialogShownState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, NSDictionary> {
                        withTransaction(transaction) {
                            data = FaceMatcherLivenessPromptDialogData(
                                state: state as! PromptDialogModelDialogShownState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, NSDictionary>
                            )
                        }
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
                    let parameters = data.state.parameters!
                    if parameters.usesCustomUserInterface {
                        let faceMatcherLivenessSession = parameters.faceMatcherLivenessSession
                        for await state in faceMatcherLivenessSession.state {
                            if state.status == FaceMatcherLivenessPromptState.Status.success {
                                let capturedImages = state.capturedImages
                                faceMatcherLivenessSession.cancel()
                                try? await data.state.resultChannel.send(element: capturedImages)
                                var transaction = Transaction()
                                transaction.disablesAnimations = true
                                withTransaction(transaction) {
                                    self.data = nil
                                }
                                break
                            } else if state.status == FaceMatcherLivenessPromptState.Status.failed {
                                faceMatcherLivenessSession.cancel()
                                data.state.resultChannel.close(cause: PromptDismissedException())
                                var transaction = Transaction()
                                transaction.disablesAnimations = true
                                withTransaction(transaction) {
                                    self.data = nil
                                }
                                break
                            }
                        }
                    } else {
                        humanReadableReason = await toHumanReadable(
                            parameters.reason,
                            nil
                        )
                    }
                } else {
                    humanReadableReason = nil
                }
            }
            .fullScreenCover(
                item: Binding(
                    get: {
                        if let data, !data.state.parameters!.usesCustomUserInterface {
                            return data
                        }
                        return nil
                    },
                    set: { data = $0 }
                )
            ) { data in
                let faceMatcherLivenessSession = data.state.parameters!.faceMatcherLivenessSession
                let requireConfirmation = data.state.parameters!.requireConfirmation
                    || (humanReadableReason?.requireConfirmation == true)
                FaceMatcherLivenessPromptView(
                    title: humanReadableReason?.title ?? "Verify liveness",
                    subtitle: humanReadableReason?.subtitle ?? "Look at the camera and follow the instructions",
                    requireConfirmation: requireConfirmation,
                    faceMatcherLivenessSession: faceMatcherLivenessSession,
                    onSuccess: { capturedImages in
                        faceMatcherLivenessSession.cancel()
                        Task {
                            try await data.state.resultChannel.send(element: capturedImages)
                            var transaction = Transaction()
                            transaction.disablesAnimations = true
                            withTransaction(transaction) {
                                self.data = nil
                            }
                        }
                    },
                    onCancel: {
                        faceMatcherLivenessSession.cancel()
                        data.state.resultChannel.close(cause: PromptDismissedException())
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) {
                            self.data = nil
                        }
                    }
                )
                .presentationBackground(.clear)
            }
    }
}

private struct FaceMatcherPromptView: View {
    let title: String
    let subtitle: String
    let requireConfirmation: Bool
    let faceMatcherSession: FaceMatcherSession
    let onSuccess: () -> Void
    let onCancel: () -> Void

    @State private var promptState: FaceMatcherPromptState? = nil
    @State private var initialBrightness: CGFloat = UIScreen.main.brightness
    @State private var dialogOpacity: Double = 0.0
    @State private var isDismissing: Bool = false

    private var resolvedBackgroundColor: Color {
        if let bg = promptState?.backgroundColor {
            return Color(
                red: Double(bg.red),
                green: Double(bg.green),
                blue: Double(bg.blue),
                opacity: Double(bg.alpha)
            )
        }
        return .white
    }

    private var isLightBackground: Bool {
        if let bg = promptState?.backgroundColor, bg.alpha >= 0.5 {
            return bg.luminance() > 0.5
        }
        return true
    }

    private var primaryContentColor: Color {
        isLightBackground ? .black : .white
    }

    private var secondaryContentColor: Color {
        isLightBackground ? .black.opacity(0.7) : .white.opacity(0.7)
    }

    var body: some View {
        ZStack {
            Color.white
                .ignoresSafeArea()
            if promptState?.backgroundColor != nil {
                resolvedBackgroundColor
                    .ignoresSafeArea()
            }

            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    Button {
                        guard !isDismissing else { return }
                        isDismissing = true
                        withAnimation(.easeInOut(duration: 0.2)) {
                            dialogOpacity = 0.0
                        }
                        Task {
                            try? await Task.sleep(nanoseconds: 200_000_000)
                            onCancel()
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(primaryContentColor)
                            .padding(12)
                    }
                }
                .padding(.top, 8)
                .padding(.horizontal, 12)

                Spacer()

                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Text(title)
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(primaryContentColor)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity, alignment: .center)

                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.body)
                                .foregroundColor(secondaryContentColor)
                                .multilineTextAlignment(.center)
                        }
                    }

                    ZStack {
                        CameraPreview(onFrame: { frame, completion in
                            guard !isDismissing,
                                  promptState?.status != FaceMatcherPromptState.Status.success,
                                  promptState?.status != FaceMatcherPromptState.Status.failed else {
                                completion()
                                return
                            }
                            Task {
                                _ = try? await faceMatcherSession.feedFrame(frame: frame)
                                completion()
                            }
                        })
                        .frame(width: 275, height: 355)
                        .clipShape(RoundedRectangle(cornerRadius: 45))

                        if let overlay = promptState?.overlay {
                            FaceMatcherOverlayView(overlay: overlay)
                                .frame(width: 275, height: 355)
                                .clipShape(RoundedRectangle(cornerRadius: 45))
                        }

                        if promptState?.status == FaceMatcherPromptState.Status.success {
                            Circle()
                                .fill(Color(red: 0.18, green: 0.49, blue: 0.20))
                                .frame(width: 72, height: 72)
                                .overlay(
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 36, weight: .bold))
                                        .foregroundColor(.white)
                                )
                        }
                    }
                    .frame(width: 275, height: 355)
                    .contentShape(RoundedRectangle(cornerRadius: 45))
                    .onTapGesture(coordinateSpace: .local) { location in
                        let normX = Float(max(0.0, min(1.0, location.x / 275.0)))
                        let normY = Float(max(0.0, min(1.0, location.y / 355.0)))
                        faceMatcherSession.onTouchEvent(x: normX, y: normY)
                    }

                    let messageText = promptState?.message ?? ""
                    Text(messageText)
                        .font(.title3.weight(.medium))
                        .foregroundColor(promptState?.status == FaceMatcherPromptState.Status.failed ? .red : primaryContentColor)
                        .multilineTextAlignment(.center)
                        .frame(minHeight: 56)
                }
                .padding(.horizontal, 24)

                Spacer()
                Spacer()
            }
            .padding(.bottom, 24)

            if requireConfirmation {
                VStack {
                    Spacer()
                    HStack {
                        Button {
                            guard !isDismissing else { return }
                            isDismissing = true
                            withAnimation(.easeInOut(duration: 0.2)) {
                                dialogOpacity = 0.0
                            }
                            Task {
                                try? await Task.sleep(nanoseconds: 200_000_000)
                                onCancel()
                            }
                        } label: {
                            Text("Cancel")
                                .fontWeight(.semibold)
                                .foregroundColor(primaryContentColor)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        Button {
                            guard !isDismissing else { return }
                            isDismissing = true
                            withAnimation(.easeInOut(duration: 0.2)) {
                                dialogOpacity = 0.0
                            }
                            Task {
                                try? await Task.sleep(nanoseconds: 200_000_000)
                                onSuccess()
                            }
                        } label: {
                            Text("Confirm")
                                .fontWeight(.semibold)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .clipShape(Capsule())
                        .disabled(promptState?.status != FaceMatcherPromptState.Status.success)
                        .environment(\.colorScheme, isLightBackground ? .light : .dark)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 16)
                }
            }
        }
        .opacity(dialogOpacity)
        .onAppear {
            initialBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 1.0
            withAnimation(.easeInOut(duration: 0.2)) {
                dialogOpacity = 1.0
            }
        }
        .onDisappear {
            UIScreen.main.brightness = initialBrightness
            faceMatcherSession.cancel()
        }
        .task {
            let initial = faceMatcherSession.state.value
            promptState = initial
            startSimulationTimerIfNeeded()
            for await state in faceMatcherSession.state {
                self.promptState = state
                if state.status == FaceMatcherPromptState.Status.success {
                    if !requireConfirmation {
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        guard !isDismissing else { break }
                        isDismissing = true
                        withAnimation(.easeInOut(duration: 0.2)) {
                            dialogOpacity = 0.0
                        }
                        try? await Task.sleep(nanoseconds: 200_000_000)
                        onSuccess()
                    }
                } else if state.status == FaceMatcherPromptState.Status.failed {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    guard !isDismissing else { break }
                    isDismissing = true
                    withAnimation(.easeInOut(duration: 0.2)) {
                        dialogOpacity = 0.0
                    }
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    onCancel()
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

private struct FaceMatcherLivenessPromptView: View {
    let title: String
    let subtitle: String
    let requireConfirmation: Bool
    let faceMatcherLivenessSession: FaceMatcherLivenessSession
    let onSuccess: ([FacePose: ByteString]) -> Void
    let onCancel: () -> Void

    @State private var promptState: FaceMatcherLivenessPromptState? = nil
    @State private var initialBrightness: CGFloat = UIScreen.main.brightness
    @State private var dialogOpacity: Double = 0.0
    @State private var isDismissing: Bool = false

    private var resolvedBackgroundColor: Color {
        if let bg = promptState?.backgroundColor {
            return Color(
                red: Double(bg.red),
                green: Double(bg.green),
                blue: Double(bg.blue),
                opacity: Double(bg.alpha)
            )
        }
        return .white
    }

    private var isLightBackground: Bool {
        if let bg = promptState?.backgroundColor, bg.alpha >= 0.5 {
            return bg.luminance() > 0.5
        }
        return true
    }

    private var primaryContentColor: Color {
        isLightBackground ? .black : .white
    }

    private var secondaryContentColor: Color {
        isLightBackground ? .black.opacity(0.7) : .white.opacity(0.7)
    }

    var body: some View {
        ZStack {
            Color.white
                .ignoresSafeArea()
            if promptState?.backgroundColor != nil {
                resolvedBackgroundColor
                    .ignoresSafeArea()
            }

            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    Button {
                        guard !isDismissing else { return }
                        isDismissing = true
                        withAnimation(.easeInOut(duration: 0.2)) {
                            dialogOpacity = 0.0
                        }
                        Task {
                            try? await Task.sleep(nanoseconds: 200_000_000)
                            onCancel()
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(primaryContentColor)
                            .padding(12)
                    }
                }
                .padding(.top, 8)
                .padding(.horizontal, 12)

                Spacer()

                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Text(title)
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(primaryContentColor)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity, alignment: .center)

                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.body)
                                .foregroundColor(secondaryContentColor)
                                .multilineTextAlignment(.center)
                        }
                    }

                    ZStack {
                        CameraPreview(onFrame: { frame, completion in
                            guard !isDismissing,
                                  promptState?.status != FaceMatcherLivenessPromptState.Status.success,
                                  promptState?.status != FaceMatcherLivenessPromptState.Status.failed else {
                                completion()
                                return
                            }
                            Task {
                                _ = try? await faceMatcherLivenessSession.feedFrame(frame: frame)
                                completion()
                            }
                        })
                        .frame(width: 275, height: 355)
                        .clipShape(RoundedRectangle(cornerRadius: 45))

                        if let overlay = promptState?.overlay {
                            FaceMatcherOverlayView(overlay: overlay)
                                .frame(width: 275, height: 355)
                                .clipShape(RoundedRectangle(cornerRadius: 45))
                        }

                        if promptState?.status == FaceMatcherLivenessPromptState.Status.success {
                            Circle()
                                .fill(Color(red: 0.18, green: 0.49, blue: 0.20))
                                .frame(width: 72, height: 72)
                                .overlay(
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 36, weight: .bold))
                                        .foregroundColor(.white)
                                )
                        }
                    }
                    .frame(width: 275, height: 355)
                    .contentShape(RoundedRectangle(cornerRadius: 45))
                    .onTapGesture(coordinateSpace: .local) { location in
                        let normX = Float(max(0.0, min(1.0, location.x / 275.0)))
                        let normY = Float(max(0.0, min(1.0, location.y / 355.0)))
                        faceMatcherLivenessSession.onTouchEvent(x: normX, y: normY)
                    }

                    let messageText = promptState?.message ?? ""
                    Text(messageText)
                        .font(.title3.weight(.medium))
                        .foregroundColor(promptState?.status == FaceMatcherLivenessPromptState.Status.failed ? .red : primaryContentColor)
                        .multilineTextAlignment(.center)
                        .frame(minHeight: 56)
                }
                .padding(.horizontal, 24)

                Spacer()
                Spacer()
            }
            .padding(.bottom, 24)

            if requireConfirmation {
                VStack {
                    Spacer()
                    HStack {
                        Button {
                            guard !isDismissing else { return }
                            isDismissing = true
                            withAnimation(.easeInOut(duration: 0.2)) {
                                dialogOpacity = 0.0
                            }
                            Task {
                                try? await Task.sleep(nanoseconds: 200_000_000)
                                onCancel()
                            }
                        } label: {
                            Text("Cancel")
                                .fontWeight(.semibold)
                                .foregroundColor(primaryContentColor)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        Button {
                            guard !isDismissing, let state = promptState else { return }
                            let capturedImages = state.capturedImages
                            isDismissing = true
                            withAnimation(.easeInOut(duration: 0.2)) {
                                dialogOpacity = 0.0
                            }
                            Task {
                                try? await Task.sleep(nanoseconds: 200_000_000)
                                onSuccess(capturedImages)
                            }
                        } label: {
                            Text("Confirm")
                                .fontWeight(.semibold)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .clipShape(Capsule())
                        .disabled(promptState?.status != FaceMatcherLivenessPromptState.Status.success)
                        .environment(\.colorScheme, isLightBackground ? .light : .dark)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 16)
                }
            }
        }
        .opacity(dialogOpacity)
        .onAppear {
            initialBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 1.0
            withAnimation(.easeInOut(duration: 0.2)) {
                dialogOpacity = 1.0
            }
        }
        .onDisappear {
            UIScreen.main.brightness = initialBrightness
            faceMatcherLivenessSession.cancel()
        }
        .task {
            let initial = faceMatcherLivenessSession.state.value
            promptState = initial
            startSimulationTimerIfNeeded()
            for await state in faceMatcherLivenessSession.state {
                self.promptState = state
                if state.status == FaceMatcherLivenessPromptState.Status.success {
                    if !requireConfirmation {
                        let capturedImages = state.capturedImages
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        guard !isDismissing else { break }
                        isDismissing = true
                        withAnimation(.easeInOut(duration: 0.2)) {
                            dialogOpacity = 0.0
                        }
                        try? await Task.sleep(nanoseconds: 200_000_000)
                        onSuccess(capturedImages)
                    }
                } else if state.status == FaceMatcherLivenessPromptState.Status.failed {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    guard !isDismissing else { break }
                    isDismissing = true
                    withAnimation(.easeInOut(duration: 0.2)) {
                        dialogOpacity = 0.0
                    }
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    onCancel()
                }
            }
        }
    }

    private func startSimulationTimerIfNeeded() {
        #if targetEnvironment(simulator)
        Task {
            while promptState?.status == nil || promptState?.status == FaceMatcherLivenessPromptState.Status.inProgress {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard promptState?.status == nil || promptState?.status == FaceMatcherLivenessPromptState.Status.inProgress else { break }
                let frame = CameraFrame(
                    width: 640,
                    height: 480,
                    rotationDegrees: 0,
                    platformHandle: nil
                )
                _ = try? await faceMatcherLivenessSession.feedFrame(frame: frame)
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
    private let sessionQueue = DispatchQueue(label: "org.multipaz.camera_session_queue", qos: .userInitiated)
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

        let session = self.captureSession
        sessionQueue.async {
            if !session.isRunning {
                session.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        videoOutput.setSampleBufferDelegate(nil, queue: nil)
        let session = self.captureSession
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    deinit {
        videoOutput.setSampleBufferDelegate(nil, queue: nil)
        let session = self.captureSession
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
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
