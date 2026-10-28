import SwiftUI
import UIKit
import Multipaz

/// Sample `FaceMatcher` implementation for iOS that uses a custom user interface
/// (`usesCustomUserInterface = true`) by presenting its own full-screen view controller
/// for both face matching and liveness verification.
final class CustomUiFaceMatcher: FaceMatcher {
    let name: String = "custom_ui"
    let displayName: String = "Custom UI"
    let supportsLiveness: Bool = true
    let usesCustomUserInterface: Bool = true

    private let getStorage: () -> Storage?

    init(getStorage: @escaping () -> Storage? = { nil }) {
        self.getStorage = getStorage
    }

    func createSession(referencePortrait: ByteString) -> FaceMatcherSession {
        let image = UIImage(data: referencePortrait.toNSData())
        let session = CustomUiFaceMatcherSession(referencePortrait: referencePortrait, image: image)
        Task { @MainActor in
            session.presentCustomUi()
        }
        return session
    }

    func createLivenessSession(poses: [FacePose]) throws -> FaceMatcherLivenessSession {
        guard !poses.isEmpty else {
            throw NSError(
                domain: "CustomUiFaceMatcher",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "At least one pose must be specified"]
            )
        }
        let session = CustomUiFaceMatcherLivenessSession(
            poses: poses,
            getStorage: getStorage
        )
        Task { @MainActor in
            await session.presentCustomUi()
        }
        return session
    }
}

final class CustomUiFaceMatcherSession: FaceMatcherSession {
    let image: UIImage?
    private weak var hostingController: UIViewController?
    private var isCompleted = false

    init(referencePortrait: ByteString, image: UIImage?) {
        self.image = image
        super.init(referencePortrait: referencePortrait)
    }

    @MainActor
    func presentCustomUi() {
        guard !isCompleted else { return }
        guard let topController = CustomUiTopViewControllerFinder.topViewController() else {
            simulateCancel()
            return
        }

        let view = CustomUiFaceMatcherView(
            heading: "Custom Face Matcher UI",
            blurb: "This is a FaceMatcher implementation with usesCustomUserInterface = true that presents its own view controller. It shows the reference portrait to match against and lets you cancel or simulate a successful match.",
            image: image,
            confirmButtonLabel: "Simulate Match",
            onCancel: { [weak self] in
                self?.simulateCancel()
            },
            onConfirm: { [weak self] in
                self?.simulateMatch()
            }
        )
        let controller = UIHostingController(rootView: view)
        controller.modalPresentationStyle = .fullScreen
        controller.isModalInPresentation = true
        self.hostingController = controller
        topController.present(controller, animated: true)
    }

    func simulateMatch() {
        guard !isCompleted else { return }
        isCompleted = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let controller = self.hostingController {
                self.hostingController = nil
                controller.dismiss(animated: true) {
                    self.updateState(
                        message: "Matched",
                        status: .success,
                        overlay: nil,
                        backgroundColor: nil
                    )
                }
            } else {
                self.updateState(
                    message: "Matched",
                    status: .success,
                    overlay: nil,
                    backgroundColor: nil
                )
            }
        }
    }

    func simulateCancel() {
        guard !isCompleted else { return }
        isCompleted = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let controller = self.hostingController {
                self.hostingController = nil
                controller.dismiss(animated: true) {
                    self.updateState(
                        message: "Canceled",
                        status: .failed,
                        overlay: nil,
                        backgroundColor: nil
                    )
                }
            } else {
                self.updateState(
                    message: "Canceled",
                    status: .failed,
                    overlay: nil,
                    backgroundColor: nil
                )
            }
        }
    }

    override func cancel() {
        isCompleted = true
        Task { @MainActor [weak self] in
            if let controller = self?.hostingController {
                self?.hostingController = nil
                controller.dismiss(animated: true)
            }
        }
    }
}

final class CustomUiFaceMatcherLivenessSession: FaceMatcherLivenessSession {
    private let getStorage: () -> Storage?
    private weak var hostingController: UIViewController?
    private var isCompleted = false

    init(poses: [FacePose], getStorage: @escaping () -> Storage?) {
        self.getStorage = getStorage
        super.init(poses: poses)
    }

    @MainActor
    func presentCustomUi() async {
        guard !isCompleted else { return }
        let defaultPortrait = FaceTestData.shared.allPortraits.first!.data
        var portraitBytes = defaultPortrait
        if let storage = getStorage(),
           let stored = await getFaceMatcherReferencePortrait(storage: storage),
           stored.size > 0 {
            portraitBytes = stored
        }
        guard !isCompleted else { return }
        guard let topController = CustomUiTopViewControllerFinder.topViewController() else {
            simulateCancel()
            return
        }

        let image = UIImage(data: portraitBytes.toNSData())
        let posesDescription = poses.map(\.poseToken).joined(separator: ", ")
        let view = CustomUiFaceMatcherView(
            heading: "Custom Face Liveness UI",
            blurb: "This is a FaceMatcher implementation with usesCustomUserInterface = true that presents its own view controller for liveness verification. Required poses: \(posesDescription). Tapping Continue returns the reference portrait below for each requested pose.",
            image: image,
            confirmButtonLabel: "Continue",
            onCancel: { [weak self] in
                self?.simulateCancel()
            },
            onConfirm: { [weak self] in
                self?.simulateContinue(portrait: portraitBytes)
            }
        )
        let controller = UIHostingController(rootView: view)
        controller.modalPresentationStyle = .fullScreen
        controller.isModalInPresentation = true
        self.hostingController = controller
        topController.present(controller, animated: true)
    }

    func simulateContinue(portrait: ByteString) {
        guard !isCompleted else { return }
        isCompleted = true
        let captured = Dictionary(uniqueKeysWithValues: poses.map { ($0, portrait) })
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let controller = self.hostingController {
                self.hostingController = nil
                controller.dismiss(animated: true) {
                    self.updateState(
                        message: "Completed",
                        status: .success,
                        overlay: nil,
                        capturedImages: captured,
                        backgroundColor: nil
                    )
                }
            } else {
                self.updateState(
                    message: "Completed",
                    status: .success,
                    overlay: nil,
                    capturedImages: captured,
                    backgroundColor: nil
                )
            }
        }
    }

    func simulateCancel() {
        guard !isCompleted else { return }
        isCompleted = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let controller = self.hostingController {
                self.hostingController = nil
                controller.dismiss(animated: true) {
                    self.updateState(
                        message: "Canceled",
                        status: .failed,
                        overlay: nil,
                        capturedImages: [:],
                        backgroundColor: nil
                    )
                }
            } else {
                self.updateState(
                    message: "Canceled",
                    status: .failed,
                    overlay: nil,
                    capturedImages: [:],
                    backgroundColor: nil
                )
            }
        }
    }

    override func cancel() {
        isCompleted = true
        Task { @MainActor [weak self] in
            if let controller = self?.hostingController {
                self?.hostingController = nil
                controller.dismiss(animated: true)
            }
        }
    }
}

private enum CustomUiTopViewControllerFinder {
    @MainActor
    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive }
        guard let root = scenes.first?.windows.first(where: { $0.isKeyWindow })?.rootViewController
            ?? scenes.first?.windows.first?.rootViewController else {
            return nil
        }
        var current = root
        while let presented = current.presentedViewController {
            current = presented
        }
        return current
    }
}

private struct CustomUiFaceMatcherView: View {
    let heading: String
    let blurb: String
    let image: UIImage?
    let confirmButtonLabel: String
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()

            Text(heading)
                .font(.title2)
                .fontWeight(.semibold)

            Text(blurb)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Spacer().frame(height: 8)

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 240, height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            } else {
                Text("No reference portrait available")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            Spacer().frame(height: 16)

            HStack(spacing: 16) {
                Button(action: onCancel) {
                    Text("Cancel")
                        .fontWeight(.medium)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.bordered)

                Button(action: onConfirm) {
                    Text(confirmButtonLabel)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
            }

            Spacer()
        }
        .padding(24)
        .background(Color(.systemBackground))
    }
}
