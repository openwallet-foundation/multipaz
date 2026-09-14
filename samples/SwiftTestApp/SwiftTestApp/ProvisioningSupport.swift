import Foundation
import Multipaz

/// Imitate OpenID4VCI wallet back-end for the test app and provide support for the app links.
///
/// In a real wallet app, the app should call its back-end server to implement OpenID4VCIBackend
/// interface, as keys that are used to sign various attestations and assertions must be kept
/// secret. For testing purposes the keys are embedded into the app itself - but such app can be
/// easily impersonated and therefore can never be trusted by a real-life provisioning server.
class ProvisioningSupport {
    
    static let APP_LINK_HOST = "apps.multipaz.org"
    static let APP_LINK_SERVER = "https://\(APP_LINK_HOST)"
    static let APP_LINK_BASE_URL = "\(APP_LINK_SERVER)/landing/"

    private static let TAG = "ProvisioningSupport"

    static let BACKEND_SERVER_URL: String? = nil

    let storage: Storage
    let secureArea: SecureArea

    private let lock = NSLock()
    private var pendingLinksByState: [String: CheckedContinuation<String, Never>] = [:]

    private var backend: OpenID4VCIBackend!
    private var preferences: OpenID4VCIClientPreferences!
    
    init(storage: Storage, secureArea: SecureArea) {
        self.storage = storage
        self.secureArea = secureArea
    }

    func initialize() async {
        var backend: OpenID4VCIBackend? = nil

        if let backendServerUrl = ProvisioningSupport.BACKEND_SERVER_URL {
            do {
                let rpcAuthorizedClient = try await RpcAuthorizedDeviceClient.companion.connect(
                    exceptionMap: RpcExceptionMap.Builder().build(),
                    httpClientEngine: Darwin(),
                    url: "\(backendServerUrl)/rpc",
                    secureArea: secureArea,
                    storage: storage,
                    secret: nil
                )
                backend = OpenID4VCIBackendStub(
                    endpoint: "openid4vci_backend",
                    dispatcher: rpcAuthorizedClient.dispatcher,
                    notifier: rpcAuthorizedClient.notifier,
                    state: Bstr(value: KotlinByteArray(size: 0))
                )
            } catch {
                Logger.shared.e(tag: ProvisioningSupport.TAG, msg: "Error connecting to back-end: \(error)")
            }
        }
        
        if backend == nil {
            backend = OpenID4VCILocalBackend(
                clientAssertionKey: try! AsymmetricKey.companion.parseExplicit(json:
                    """
                    {
                        "kty": "EC",
                        "alg": "ES256",
                        "kid": "895b72b9-0808-4fcc-bb19-960d14a9e28f",
                        "crv": "P-256",
                        "x": "nSmAFnZx-SqgTEyqqOSmZyLESdbiSUIYlRlLLoWy5uc",
                        "y": "FN1qcif7nyVX1MHN_YSbo7o7RgG2kPJUjg27YX6AKsQ",
                        "d": "TdQhxDqbAUpzMJN5XXQqLea7-6LvQu2GFKzj5QmFDCw"
                    }            
                    """.trimmingCharacters(in: .whitespacesAndNewlines)
                ),
                attestationKey: try! AsymmetricKey.companion.parseExplicit(json:
                    """
                    {
                        "kty": "EC",
                        "alg": "ES256",
                        "crv": "P-256",
                        "x": "CoLFZ9sJfTqax-GarKIyw7_fX8-L446AoCTSHKJnZGs",
                        "y": "ALEJB1_YQMO_0qSFQb3urFTxRfANN8-MSeWLHYU7MVI",
                        "d": "nJXw7FqLff14yQLBEAwu70mu1gzlfOONh9UuealdsVM",
                        "x5c": [
                            "MIIBtDCCATugAwIBAgIJAPosC/l8rotwMAoGCCqGSM49BAMCMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAeFw0yNTA5MzAwMjUxNDRaFw0zNTA5MjgwMjUxNDRaMDgxNjA0BgNVBAMMLXVybjp1dWlkOjRjNDY0NzJiLTdlYjItNDRiNi04NTNhLWY3ZGZlMTEzYzU3NTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAqCxWfbCX06msfhmqyiMsO/31/Pi+OOgKAk0hyiZ2RrALEJB1/YQMO/0qSFQb3urFTxRfANN8+MSeWLHYU7MVKjLjAsMB8GA1UdIwQYMBaAFPqAK5EjiQbxFAeWt//DCaWtC57aMAkGA1UdEwQCMAAwCgYIKoZIzj0EAwIDZwAwZAIwfDEviit5J188zK5qKjkzFWkPy3ljshUg650p2kNuQq7CiQvbKyVDIlCGgOhMZyy+AjBm6ehDicFMPVBEHLUEiXO4cHw7Ed6dFpPm/6GknWcADhax62KN1tIzExo6T1l06G4=",
                            "MIIBxTCCAUugAwIBAgIJAOQTL9qcQopZMAoGCCqGSM49BAMDMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAeFw0yNDA5MjMyMjUxMzFaFw0zNDA5MjMyMjUxMzFaMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjB2MBAGByqGSM49AgEGBSuBBAAiA2IABN4D7fpNMAv4EtxyschbITpZ6iNH90rGapa6YEO/uhKnC6VpPt5RUrJyhbvwAs0edCPthRfIZwfwl5GSEOS0mKGCXzWdRv4GGX/Y0m7EYypox+tzfnRTmoVX3v6OxQiapKMhMB8wHQYDVR0OBBYEFPqAK5EjiQbxFAeWt//DCaWtC57aMAoGCCqGSM49BAMDA2gAMGUCMEO01fJKCy+iOTpaVp9LfO7jiXcXksn2BA22reiR9ahDRdGNCrH1E3Q2umQAssSQbQIxAIz1FTHbZPcEbA5uE5lCZlRG/DQxlZhk/rZrkPyXFhqEgfMnQ45IJ6f8Utlg+4Wiiw=="
                        ]
                    }
                    """.trimmingCharacters(in: .whitespacesAndNewlines)
                ),
                clientId: "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4",
                walletName: "Multipaz Swift TestApp",
                walletLink: "https://apps.multipaz.org"
            )
        }
        
        // Force unwrapped as we ensure assignment above
        self.backend = backend!
        
        let clientId = try! await self.backend.getClientId()
        
        self.preferences = OpenID4VCIClientPreferences(
            clientId: clientId,
            redirectUrl: ProvisioningSupport.APP_LINK_BASE_URL,
            locales: ["en-US"],
            signingAlgorithms: [.esp256, .esp384, .esp512]
        )
    }

    func processAppLinkInvocation(url: String) async {
        guard let urlComponents = URLComponents(string: url),
              let queryItems = urlComponents.queryItems,
              let state = queryItems.first(where: { $0.name == "state" })?.value else {
            return
        }

        lock.lock()
        let continuation = pendingLinksByState.removeValue(forKey: state)
        lock.unlock()
        
        // Resume the continuation if one exists for this state
        continuation?.resume(returning: url)
    }

    func waitForAppLinkInvocation(state: String) async -> String {
        return await withCheckedContinuation { continuation in
            lock.lock()
            // In Kotlin's Channel.RENDEZVOUS, the sender waits for receiver or vice versa.
            // Here we register the receiver (continuation) and wait for processAppLinkInvocation to resume it.
            pendingLinksByState[state] = continuation
            lock.unlock()
        }
    }

    func getOpenID4VCIClientPreferences() -> OpenID4VCIClientPreferences {
        return preferences
    }

    func getOpenID4VCIBackend() -> OpenID4VCIBackend {
        return backend
    }
}
