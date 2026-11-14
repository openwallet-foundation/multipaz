import SwiftUI
import Multipaz

struct Iso18013ProximityPresentmentScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    var body: some View {
        VStack {
            MdocProximityQrPresentment(
                source: viewModel.getSource(),
                prepareSettings: { generateQrCode in
                    MdocProximityReaderQrPresentment(
                        source: viewModel.getSource(),
                        prepareSettings: { scanReaderQrCode in
                            @Bindable var viewModel = viewModel
                            VStack(spacing: 24) {
                                HStack {
                                    Text("Face matcher:")
                                    Spacer()
                                    Picker("Face Matcher", selection: $viewModel.selectedFaceMatcher) {
                                        Text("None").tag("None")
                                        Text("Facenet").tag("facenet")
                                        Text("Facenet (debug)").tag("facenet_debug")
                                    }
                                    .pickerStyle(.menu)
                                }
                                .padding(.horizontal)

                                Button("Share mdoc using QR code") {
                                    let bleUuid = UUID.companion.randomUUID(random: KotlinRandom.companion)
                                    let connectionMethods: [MdocConnectionMethod] = [
                                        MdocConnectionMethod.Ble.DirectL2capPeripheral(
                                            uuid: bleUuid,
                                            psm: nil,
                                            peripheralAddress: nil
                                        ),
                                        MdocConnectionMethod.Ble.GattPeripheral(
                                            uuid: bleUuid,
                                            peripheralAddress: nil
                                        )
                                    ]
                                    let settings = MdocProximityQrSettings(
                                        availableConnectionMethods: connectionMethods,
                                        createTransportOptions: MdocTransportOptions(
                                            bleUseL2CAP: false
                                        )
                                    )
                                    generateQrCode(settings)
                                }
                                .buttonStyle(.borderedProminent)
                                .buttonBorderShape(.capsule)

                                Button("Scan Reader QR code") {
                                    let settings = MdocProximityReaderQrSettings(
                                        preferredConnectionMethodOrder: [
                                            "ble:direct_l2cap_central:",
                                            "ble:gatt_central:",
                                            "ble:gatt_peripheral:"
                                        ],
                                        bleDirectL2capEnabled: true,
                                        createTransportOptions: MdocTransportOptions(
                                            bleUseL2CAP: false
                                        )
                                    )
                                    scanReaderQrCode(settings)
                                }
                                .buttonStyle(.borderedProminent)
                                .buttonBorderShape(.capsule)
                            }
                        },
                        scanQrCode: { onQrCodeScanned, cancel in
                            VStack(spacing: 16) {
                                Text("Scan Reader QR code")
                                    .font(.headline)
                                ScanQrCodeView(onCodeScanned: onQrCodeScanned)
                                    .frame(maxWidth: .infinity, maxHeight: 360)
                                    .clipShape(RoundedRectangle(cornerRadius: 16))
                                    .padding(.horizontal)
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

