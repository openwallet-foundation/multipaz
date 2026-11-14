import Combine
import SwiftUI

private enum ReaderQrPresentmentState {
    case prepareSettings
    case scanQrCode
    case transacting
    case completed
}

@MainActor
final class MdocReaderQrPresentmentViewModel: ObservableObject {
    @Published fileprivate var state: ReaderQrPresentmentState = .prepareSettings
    @Published var transactionError: Error?

    private var activeSettings: MdocProximityReaderQrSettings?
    private var transactionTask: Task<Void, Never>?

    let source: PresentmentSource
    let preselectedDocuments: [MultipazDocument]
    let supportedCurves: [EcCurve]
    let transportFactory: MdocTransportFactory

    init(
        source: PresentmentSource,
        preselectedDocuments: [MultipazDocument],
        supportedCurves: [EcCurve],
        transportFactory: MdocTransportFactory
    ) {
        self.source = source
        self.preselectedDocuments = preselectedDocuments
        self.supportedCurves = supportedCurves
        self.transportFactory = transportFactory
    }

    func startScanning(settings: MdocProximityReaderQrSettings) {
        self.transactionError = nil
        self.activeSettings = settings
        self.state = .scanQrCode
    }

    func onQrCodeScanned(uri: String) -> Bool {
        guard state == .scanQrCode,
              let qrSettings = activeSettings,
              uri.hasPrefix("mdoc:"),
              !uri.hasPrefix("mdoc://"),
              uri.count > 5 else {
            return false
        }

        self.state = .transacting
        transactionTask = Task {
            do {
                try await Iso18013ReaderQrPresentment(
                    uri: uri,
                    source: source,
                    transportFactory: transportFactory,
                    options: qrSettings.createTransportOptions,
                    preferredConnectionMethodOrder: qrSettings.preferredConnectionMethodOrder,
                    bleDirectL2capEnabled: qrSettings.bleDirectL2capEnabled,
                    supportedCurves: supportedCurves,
                    capabilities: [
                        Capability.readerAuthAllSupport: Simple.companion.TRUE,
                        Capability.extendedRequestSupport: Simple.companion.TRUE
                    ],
                    preselectedDocuments: preselectedDocuments,
                    connectionTimeout: KotlinDurationCompanion.shared.fromSeconds(seconds: 15),
                    timeout: KotlinDurationCompanion.shared.fromSeconds(seconds: 15),
                    timeoutSubsequentRequests: KotlinDurationCompanion.shared.fromSeconds(seconds: 30),
                    onConnecting: onReaderQrConnecting,
                    onWaitingForRequest: onReaderQrWaitingForRequest,
                    onWaitingForUserInput: onReaderQrWaitingForUserInput,
                    onDocumentsInFocus: onReaderQrDocumentsInFocus,
                    onSendingResponse: onReaderQrSendingResponse,
                    onDeviceRequest: onReaderQrDeviceRequest,
                    onTelemetry: { _ in }
                )
            } catch {
                if !Task.isCancelled {
                    self.transactionError = error
                }
            }

            if !Task.isCancelled {
                self.state = .completed
                self.transactionTask = nil
            }
        }
        return true
    }

    func cancelTransaction() {
        transactionTask?.cancel()
        transactionTask = nil
        activeSettings = nil
    }

    func reset() {
        self.state = .prepareSettings
    }
}

private func onReaderQrConnecting() -> Void {}
private func onReaderQrWaitingForRequest() -> Void {}
private func onReaderQrWaitingForUserInput() -> Void {}
private func onReaderQrDocumentsInFocus(_ documents: [MultipazDocument]) -> Void {}
private func onReaderQrSendingResponse() -> Void {}
private func onReaderQrDeviceRequest(_ deviceRequest: DeviceRequest) -> Void {}

/// A SwiftUI view for holder-side presentment with Reader QR engagement according to ISO/IEC 18013-5 Second Edition.
@MainActor
public struct MdocProximityReaderQrPresentment<
    PrepareSettingsContent: View,
    ScanQrCodeContent: View,
    ShowTransactingContent: View,
    ShowCompletedContent: View
>: View {

    public let preselectedDocuments: [MultipazDocument]
    public let supportedCurves: [EcCurve]
    public let transportFactory: MdocTransportFactory

    @ViewBuilder public let prepareSettings: (_ scanReaderQrCode: @escaping (MdocProximityReaderQrSettings) -> Void) -> PrepareSettingsContent
    @ViewBuilder public let scanQrCode: (_ onQrCodeScanned: @escaping (String) -> Bool, _ reset: @escaping () -> Void) -> ScanQrCodeContent
    @ViewBuilder public let showTransacting: (_ reset: @escaping () -> Void) -> ShowTransactingContent
    @ViewBuilder public let showCompleted: (_ error: Error?, _ reset: @escaping () -> Void) -> ShowCompletedContent

    @StateObject private var viewModel: MdocReaderQrPresentmentViewModel

    public init(
        source: PresentmentSource,
        preselectedDocuments: [MultipazDocument] = [],
        supportedCurves: [EcCurve] = [.p256, .p384, .p521],
        transportFactory: MdocTransportFactory = MdocTransportFactoryDefault(),
        @ViewBuilder prepareSettings: @escaping (_ scanReaderQrCode: @escaping (MdocProximityReaderQrSettings) -> Void) -> PrepareSettingsContent,
        @ViewBuilder scanQrCode: @escaping (_ onQrCodeScanned: @escaping (String) -> Bool, _ reset: @escaping () -> Void) -> ScanQrCodeContent,
        @ViewBuilder showTransacting: @escaping (_ reset: @escaping () -> Void) -> ShowTransactingContent,
        @ViewBuilder showCompleted: @escaping (_ error: Error?, _ reset: @escaping () -> Void) -> ShowCompletedContent
    ) {
        self.preselectedDocuments = preselectedDocuments
        self.supportedCurves = supportedCurves
        self.transportFactory = transportFactory
        self.prepareSettings = prepareSettings
        self.scanQrCode = scanQrCode
        self.showTransacting = showTransacting
        self.showCompleted = showCompleted

        _viewModel = StateObject(wrappedValue: MdocReaderQrPresentmentViewModel(
            source: source,
            preselectedDocuments: preselectedDocuments,
            supportedCurves: supportedCurves,
            transportFactory: transportFactory
        ))
    }

    public var body: some View {
        VStack {
            switch viewModel.state {
            case .prepareSettings:
                prepareSettings { qrSettings in
                    viewModel.startScanning(settings: qrSettings)
                }

            case .scanQrCode:
                scanQrCode(
                    { uri in viewModel.onQrCodeScanned(uri: uri) },
                    {
                        viewModel.cancelTransaction()
                        viewModel.reset()
                    }
                )

            case .transacting:
                showTransacting {
                    viewModel.cancelTransaction()
                    viewModel.reset()
                }

            case .completed:
                showCompleted(viewModel.transactionError) {
                    viewModel.reset()
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}
