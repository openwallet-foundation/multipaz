import SwiftUI
import Multipaz

/**
 * Screen demonstrating the direct embedding of [FaceMatcherLivenessCaptureView] into an
 * application workflow (such as credential provisioning).
 */
struct FaceLivenessCaptureScreen: View {
    @Environment(ViewModel.self) private var viewModel
    var matcherName: String? = nil

    @State private var session: FaceMatcherLivenessSession? = nil
    @State private var initializationError: String? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                infoCard

                if let error = initializationError {
                    errorCard(error: error)
                } else if let session {
                    captureCard(session: session)
                } else {
                    ProgressView()
                        .padding(32)
                }

                Button("Cancel") {
                    cancelAndDismiss()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            }
            .padding(16)
        }
        .navigationTitle("Check Liveness (View)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            initializeSessionIfNeeded()
        }
    }

    @ViewBuilder
    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Embedded Liveness Verification")
                .font(.headline)
                .fontWeight(.bold)
            Text("This screen embeds the FaceMatcherLivenessCaptureView directly into the screen layout rather than showing a modal bottom sheet dialog. Upon completing the liveness challenges, a portrait image will be captured for issuer approval.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private func errorCard(error: String) -> some View {
        VStack(spacing: 8) {
            Text(error)
                .font(.body)
                .foregroundColor(.red)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private func captureCard(session: FaceMatcherLivenessSession) -> some View {
        VStack {
            FaceMatcherLivenessCaptureView(
                session: session,
                onSuccess: { portraitBytes in
                    viewModel.path.append(.fakeIssuerApprovalScreen(portraitBytes: portraitBytes))
                },
                onFailed: {
                    cancelAndDismiss()
                }
            )
        }
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(.separator), lineWidth: 1)
        )
    }

    private func initializeSessionIfNeeded() {
        guard session == nil else { return }
        let matcher = (matcherName != nil ? viewModel.faceMatcherRepository?.lookup(name: matcherName!) : nil)
            ?? viewModel.faceMatcherRepository?.defaultMatcher
        guard let matcher else {
            initializationError = "Face matching is not supported on this platform."
            return
        }
        do {
            session = try matcher.createLivenessSession()
        } catch {
            initializationError = "Failed to initialize liveness session: \(error.localizedDescription)"
        }
    }

    private func cancelAndDismiss() {
        session?.cancel()
        if !viewModel.path.isEmpty {
            viewModel.path.removeLast()
        }
    }
}
