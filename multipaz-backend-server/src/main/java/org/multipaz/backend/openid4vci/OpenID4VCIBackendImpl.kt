package org.multipaz.backend.openid4vci

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.device.DeviceAttestationAndroid
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendUtil
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.RpcAuthBackendDelegate
import org.multipaz.rpc.handler.RpcAuthContext
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.server.ClientRegistrationImpl
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.server.getBaseUrl
import org.multipaz.util.validateAndroidKeyAttestation

@RpcState(
    endpoint = "openid4vci_backend",
    creatable = true
)
@CborSerializable
class OpenID4VCIBackendImpl: OpenID4VCIBackend, RpcAuthInspector by RpcAuthBackendDelegate {
    override suspend fun getClientId(): String {
        return clientId
    }

    override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
        OpenID4VCIBackendUtil.createJwtClientAssertion(
            signingKey = clientAssertionKey,
            clientId = clientId,
            authorizationServerIdentifier = authorizationServerIdentifier,
        )

    override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String {
        validateKeyAttestations(listOf(keyAttestation))
        return OpenID4VCIBackendUtil.createWalletAttestation(
            signingKey = walletAttestationKey,
            clientId = clientId,
            attestationIssuer = walletAttestationKey.subject,
            attestedKey = keyAttestation.publicKey,
            nonce = null,
            walletName = walletName,
            walletLink = walletLink,
        )
    }

    override suspend fun createJwtKeyAttestation(
        keyAttestations: List<KeyAttestation>,
        challenge: String,
        userAuthentication: List<String>?,
        keyStorage: List<String>?
    ): String {
        validateKeyAttestations(keyAttestations, challenge.encodeToByteString())
        return OpenID4VCIBackendUtil.createJwtKeyAttestation(
            signingKey = keyAttestationKey,
            attestationIssuer = keyAttestationKey.subject,
            keysToAttest = keyAttestations,
            challenge = challenge,
            userAuthentication = userAuthentication,
            keyStorage = keyStorage
        )
    }

    companion object {
        private val defaultClientAssertionKey = """
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

        private val defaultAttestationKey = """
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

        private lateinit var clientAssertionKey: AsymmetricKey
        private lateinit var walletAttestationKey: AsymmetricKey
        private lateinit var keyAttestationKey: AsymmetricKey
        private lateinit var clientId: String
        private lateinit var walletName: String
        private lateinit var walletLink: String

        suspend fun init() {
            val configuration = BackendEnvironment.getInterface(Configuration::class)!!
            val secureAreaRepository = BackendEnvironment.getInterface(SecureAreaRepository::class)!!
            clientAssertionKey = AsymmetricKey.parse(
                json = configuration.getValue("client_assertion_key") ?: defaultClientAssertionKey,
                secureAreaRepository = secureAreaRepository
            )
            walletAttestationKey = AsymmetricKey.parse(
                json = configuration.getValue("wallet_attestation_key") ?: defaultAttestationKey,
                secureAreaRepository = secureAreaRepository
            )
            keyAttestationKey = AsymmetricKey.parse(
                json = configuration.getValue("key_attestation_key") ?: defaultAttestationKey,
                secureAreaRepository = secureAreaRepository
            )
            clientId = configuration.getValue("client_id")
                ?: "urn:uuid:c4011939-b5f3-4320-9832-fcebfab91ba5"
            walletName = configuration.getValue("wallet_name")
                ?: BackendEnvironment.getBaseUrl()
            walletLink = configuration.getValue("wallet_link")
                ?: BackendEnvironment.getBaseUrl()
        }

        private suspend fun validateKeyAttestations(
            keyAttestations: List<KeyAttestation>,
            challenge: ByteString? = null
        ) {
            val deviceAttestation = RpcAuthContext.getClientDeviceAttestation()!!
            // if connected from iOS we only can validate the integrity of the RPC call
            // (which RPC machinery is already performing).
            if (deviceAttestation is DeviceAttestationAndroid) {
                val clientRequirements = ClientRegistrationImpl.getClientRequirements()
                keyAttestations.forEach {
                    validateAndroidKeyAttestation(
                        chain = it.certChain!!,
                        challenge = challenge,
                        requireGmsAttestation = clientRequirements.androidGmsAttestation,
                        requireVerifiedBootGreen = clientRequirements.androidVerifiedBootGreen,
                        requireKeyMintSecurityLevel = clientRequirements.androidRequiredKeyMintSecurityLevel,
                        requireAppSignatureCertificateDigests = clientRequirements.androidAppSignatureCertificateDigests,
                        requireAppPackages = clientRequirements.androidAppPackageNames
                    )
                }
            }
        }
    }
}