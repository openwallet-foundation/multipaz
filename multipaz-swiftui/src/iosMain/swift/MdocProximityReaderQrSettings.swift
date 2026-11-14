/// Settings for holder-side ISO/IEC 18013-5 Second Edition Reader QR presentment.
public struct MdocProximityReaderQrSettings {
    public let preferredConnectionMethodOrder: [String]
    public let bleDirectL2capEnabled: Bool
    public let createTransportOptions: MdocTransportOptions

    public init(
        preferredConnectionMethodOrder: [String] = [],
        bleDirectL2capEnabled: Bool = true,
        createTransportOptions: MdocTransportOptions = MdocTransportOptions(bleUseL2CAP: false)
    ) {
        self.preferredConnectionMethodOrder = preferredConnectionMethodOrder
        self.bleDirectL2capEnabled = bleDirectL2capEnabled
        self.createTransportOptions = createTransportOptions
    }
}
