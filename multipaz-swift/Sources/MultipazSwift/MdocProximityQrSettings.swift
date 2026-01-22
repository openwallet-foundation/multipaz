import Multipaz

public struct MdocProximityQrSettings {
    let availableConnectionMethods: [MdocConnectionMethod]
    let createTransportOptions: MdocTransportOptions
    
    public init(availableConnectionMethods: [MdocConnectionMethod], createTransportOptions: MdocTransportOptions) {
        self.availableConnectionMethods = availableConnectionMethods
        self.createTransportOptions = createTransportOptions
    }
}
