package org.multipaz.testapp.provisioning

import io.ktor.http.Url
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCILocalBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendStub
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.client.RpcAuthorizedDeviceClient
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.testapp.BuildConfig
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger

/**
 * Imitate OpenID4VCI wallet back-end for the test app and provide support for the app links.
 *
 * In a real wallet app, the app should call its back-end server to implement [OpenID4VCIBackend]
 * interface, as keys that are used to sign various attestations and assertions must be kept
 * secret. For testing purposes the keys are embedded into the app itself - but such app can be
 * easily impersonated and therefore can never be trusted by a real-life provisioning server.
 */
class ProvisioningSupport(
    val storage: Storage,
    val secureArea: SecureArea,
) {
    companion object {
        const val APP_LINK_SERVER = "https://${BuildConfig.APPLINK_HOST}"
        val APP_LINK_BASE_URL = "$APP_LINK_SERVER${TestAppConfiguration.redirectPath}"

        private const val TAG = "ProvisioningSupport"

        val BACKEND_SERVER_URL: String? = null
    }

    private val lock = Mutex()
    private val pendingLinksByState = mutableMapOf<String, SendChannel<String?>>()

    private lateinit var backend: OpenID4VCIBackend
    private lateinit var preferences: OpenID4VCIClientPreferences

    suspend fun init() {
        var backend: OpenID4VCIBackend? = null
        if (BACKEND_SERVER_URL != null) {
            try {
                val rpcAuthorizedClient = RpcAuthorizedDeviceClient.connect(
                    exceptionMap = RpcExceptionMap.Builder().build(),
                    httpClientEngine = TestAppConfiguration.httpClientEngineFactory,
                    url = "$BACKEND_SERVER_URL/rpc",
                    secureArea = secureArea,
                    storage = storage
                )
                backend = OpenID4VCIBackendStub(
                    endpoint = "openid4vci_backend",
                    dispatcher = rpcAuthorizedClient.dispatcher,
                    notifier = rpcAuthorizedClient.notifier
                )
            } catch (err: HttpTransport.ConnectionException) {
                Logger.e(TAG, "Error connecting to back-end", err)
            }
        }
        if (backend == null) {
            backend = OpenID4VCILocalBackend(
                clientAssertionKey = AsymmetricKey.parseExplicit(
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
                    """.trimIndent()
                ),
                attestationKey = AsymmetricKey.parseExplicit(
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
                    """.trimIndent()
                ),
                clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4",
                walletName = "Multipaz TestApp",
                walletLink = "https://apps.multipaz.org"
            )
        }
        this.backend = backend
        preferences = OpenID4VCIClientPreferences(
            clientId = withContext(RpcAuthClientSession()) {
                backend.getClientId()
            },
            redirectUrl = APP_LINK_BASE_URL,
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256, Algorithm.ESP384, Algorithm.ESP512)
        )
    }

    suspend fun processAppLinkInvocation(url: String) {
        val state = Url(url).parameters["state"] ?: ""
        lock.withLock {
            pendingLinksByState.remove(state)?.send(url)
        }
    }

    suspend fun cancelAllPendingAppLinks() {
        lock.withLock {
            for (channel in pendingLinksByState.values) {
                channel.trySend(null)
            }
            pendingLinksByState.clear()
        }
    }

    suspend fun waitForAppLinkInvocation(state: String): String? {
        val channel = Channel<String?>(Channel.RENDEZVOUS)
        lock.withLock {
            pendingLinksByState[state] = channel
        }
        return channel.receive()
    }

    fun getOpenID4VCIClientPreferences(): OpenID4VCIClientPreferences = preferences

    fun getOpenID4VCIBackend(): OpenID4VCIBackend = backend
}