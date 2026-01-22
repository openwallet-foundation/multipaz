import Foundation
@preconcurrency import Multipaz

extension URL {
    /// Gets the serialized origin according to the [WHATWG HTML standard](https://html.spec.whatwg.org/multipage/browsers.html#origin).
    ///
    /// - Returns: The serialized origin.
    public func getOrigin() -> String {
        var origin = "\(scheme!)://\(host!)"
        if port != nil && ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) {
            origin += ":\(port!)"
        }
        return origin
    }
}

extension Duration {
    /// Gets the duration as a number of milliseconds since the Epoch.
    ///
    /// - Returns: The duration as number of milliseconds since the Epoch.
    public func toMilliseconds() -> Int64 {
        return components.seconds*1_000 + components.attoseconds/1_000_000_000_000_000
    }
}

// Needed until it's possible to annotate in Kotlin.
//
extension Requester: @unchecked Sendable {}
extension TrustMetadata: @unchecked Sendable {}
extension Requester: @unchecked Sendable {}
extension Document: @unchecked Sendable {}
extension PresentmentSource: @unchecked Sendable {}
extension DocumentTypeRepository: @unchecked Sendable {}
extension CredentialPresentmentData: @unchecked Sendable {}
extension CredentialPresentmentSelection: @unchecked Sendable {}
