import SwiftUI
import Multipaz
import MultipazSwift

struct Iso18013ProximityPresentmentScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    var body: some View {
        VStack {
            MdocProximityQrPresentment(
                source: viewModel.getSource(),
                prepareSettings: { generateQrCode in
                    Button("Share mdoc using QR code") {
                        let bleUuid = UUID.companion.randomUUID(random: KotlinRandom.companion)
                        let connectionMethods = [
                            MdocConnectionMethodBle(
                                supportsPeripheralServerMode: true,
                                supportsCentralClientMode: false,
                                peripheralServerModeUuid: bleUuid,
                                centralClientModeUuid: nil,
                                peripheralServerModePsm: nil,
                                peripheralServerModeMacAddress: nil
                            )
                        ]
                        let settings = MdocProximityQrSettings(
                            availableConnectionMethods: connectionMethods,
                            createTransportOptions: MdocTransportOptions(
                                bleUseL2CAP: false,
                                bleUseL2CAPInEngagement: true
                            )
                        )
                        generateQrCode(settings)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                },
                showQrCode: { uri, cancel in
                    VStack {
                        Image(uiImage: generateQrCode(uri: uri))
                        Button("Cancel", action: cancel)
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                    }
                },
                showTransacting: { cancel in
                    VStack {
                        ProgressView()
                        Text("Sharing data...")
                        Button("Cancel", action: cancel)
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                    }
                },
                showCompleted: { error, reset in
                    VStack {
                        if let error = error {
                            Text("Something went wrong: \(error.localizedDescription)")
                        } else {
                            Text("The data was shared")
                        }
                    }.task {
                        try? await Task.sleep(nanoseconds: 2_000_000_000) // 2 seconds
                        reset()
                    }
                }
            )
        }
        .navigationTitle("ISO 18013-5 Proximity Presentment")
    }
}

