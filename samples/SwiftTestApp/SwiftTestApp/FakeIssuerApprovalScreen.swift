import SwiftUI
import Multipaz

/**
 * Screen asking the user for approval to send the verified captured portrait to a fake credential issuer.
 */
struct FakeIssuerApprovalScreen: View {
    @Environment(ViewModel.self) private var viewModel
    let portraitBytes: ByteString

    private var portraitImage: UIImage? {
        UIImage(data: portraitBytes.toNSData())
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(Color(red: 0.18, green: 0.49, blue: 0.20))
                    Text("Liveness Verified")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(Color(red: 0.18, green: 0.49, blue: 0.20))
                }
                .padding(.top, 8)

                if let portraitImage {
                    Image(uiImage: portraitImage)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 180, height: 230)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20)
                                .stroke(Color.accentColor, lineWidth: 2)
                        )
                } else {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(Color(.secondarySystemBackground))
                        .frame(width: 180, height: 230)
                        .overlay(
                            Text("No Portrait Available")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        )
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text("Credential Provisioning Request")
                        .font(.headline)
                        .fontWeight(.bold)

                    Divider()

                    issuerDetailRow(label: "Issuer", value: "Utopia Department of Motor Vehicles")
                    issuerDetailRow(label: "Credential", value: "Mobile Driving Licence (mDL)")
                    issuerDetailRow(label: "Biometric Status", value: "Active Liveness Verified")
                    issuerDetailRow(label: "Portrait Size", value: "\(portraitBytes.toNSData().count / 1024) KB")
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text("The issuer requires this portrait photo to issue your digital credential. Do you approve sending this portrait to the fake issuer?")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 8)

                VStack(spacing: 12) {
                    Button {
                        viewModel.pendingToastMessage = "Portrait approved and sent to fake issuer"
                        popBackToFaceMatcherPromptScreen()
                    } label: {
                        Text("Approve and Send to Issuer")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button("Cancel") {
                        viewModel.pendingToastMessage = "Provisioning cancelled"
                        popBackToFaceMatcherPromptScreen()
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                }
                .padding(.top, 8)
            }
            .padding(24)
        }
        .navigationTitle("Approve Portrait for Issuer")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func issuerDetailRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline)
                .fontWeight(.medium)
        }
    }

    private func popBackToFaceMatcherPromptScreen() {
        while viewModel.path.last != .faceMatcherPromptScreen && !viewModel.path.isEmpty {
            viewModel.path.removeLast()
        }
    }
}
