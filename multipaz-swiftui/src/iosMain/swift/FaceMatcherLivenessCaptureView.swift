import SwiftUI
import UIKit

/**
 * SwiftUI View for interactive face liveness verification and portrait photo capture.
 *
 * This view can be embedded directly into wallet screens (e.g. credential provisioning).
 * It displays the front camera preview, challenge overlay, instructions, and feeds camera
 * frames into the provided [FaceMatcherLivenessSession].
 */
public struct FaceMatcherLivenessCaptureView: View {
    public let session: FaceMatcherLivenessSession
    public var onSuccess: (ByteString) -> Void
    public var onFailed: (() -> Void)?

    @State private var promptState: FaceMatcherLivenessPromptState? = nil
    @State private var hasDeliveredSuccess: Bool = false

    public init(
        session: FaceMatcherLivenessSession,
        onSuccess: @escaping (ByteString) -> Void,
        onFailed: (() -> Void)? = nil
    ) {
        self.session = session
        self.onSuccess = onSuccess
        self.onFailed = onFailed
    }

    public var body: some View {
        VStack(spacing: 16) {
            let isSuccess = promptState?.status == FaceMatcherLivenessPromptState.Status.success
            let cornerRadius: CGFloat = 36

            ZStack {
                CameraPreview(onFrame: { frame, completion in
                    guard promptState?.status != FaceMatcherLivenessPromptState.Status.success else {
                        completion()
                        return
                    }
                    Task {
                        _ = try? await session.feedFrame(frame: frame)
                        completion()
                    }
                })
                .frame(width: 220, height: 284)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))

                if let overlay = promptState?.overlay {
                    FaceMatcherOverlayView(overlay: overlay)
                        .frame(width: 220, height: 284)
                        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                }

                if isSuccess {
                    Color.black.opacity(0.3)
                        .frame(width: 220, height: 284)
                        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))

                    Circle()
                        .fill(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .frame(width: 64, height: 64)
                        .overlay(
                            Image(systemName: "checkmark")
                                .font(.system(size: 32, weight: .bold))
                                .foregroundColor(.white)
                        )
                }
            }
            .frame(width: 220, height: 284)

            let messageText = promptState?.message ?? ""
            if !messageText.isEmpty {
                Text(messageText)
                    .font(.body)
                    .foregroundColor(promptState?.status == FaceMatcherLivenessPromptState.Status.failed ? .red : .secondary)
                    .multilineTextAlignment(.center)
                    .frame(minHeight: 44)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .onDisappear {
            session.cancel()
        }
        .task {
            promptState = session.state.value
            startSimulationTimerIfNeeded()
            for await state in session.state {
                self.promptState = state
                if state.status == FaceMatcherLivenessPromptState.Status.success, !hasDeliveredSuccess {
                    if let captured = state.capturedImage {
                        hasDeliveredSuccess = true
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        onSuccess(captured)
                    }
                } else if state.status == FaceMatcherLivenessPromptState.Status.failed {
                    onFailed?()
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
                _ = try? await session.feedFrame(frame: frame)
            }
        }
        #endif
    }
}

/**
 * UIKit UIView wrapper for [FaceMatcherLivenessCaptureView] to enable easy integration into
 * UIKit-based applications and view controllers.
 */
public class FaceMatcherLivenessCaptureUIView: UIView {
    private var hostingController: UIHostingController<FaceMatcherLivenessCaptureView>?

    public init(
        session: FaceMatcherLivenessSession,
        onSuccess: @escaping (ByteString) -> Void,
        onFailed: (() -> Void)? = nil
    ) {
        super.init(frame: .zero)
        let rootView = FaceMatcherLivenessCaptureView(
            session: session,
            onSuccess: onSuccess,
            onFailed: onFailed
        )
        let hc = UIHostingController(rootView: rootView)
        self.hostingController = hc
        hc.view.translatesAutoresizingMaskIntoConstraints = false
        hc.view.backgroundColor = .clear
        addSubview(hc.view)
        NSLayoutConstraint.activate([
            hc.view.topAnchor.constraint(equalTo: topAnchor),
            hc.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            hc.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            hc.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
