package org.multipaz.testapp.provisioning

import org.multipaz.crypto.AsymmetricKey
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendUtil
import org.multipaz.securearea.KeyAttestation

class OpenID4VCILocalBackend: OpenID4VCIBackend {
    override suspend fun getClientId(): String = CLIENT_ID
    
    override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
        OpenID4VCIBackendUtil.createJwtClientAssertion(
            signingKey = clientAssertionKey,
            clientId = CLIENT_ID,
            authorizationServerIdentifier = authorizationServerIdentifier,
        )

    override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String =
        OpenID4VCIBackendUtil.createWalletAttestation(
            signingKey = attestationKey,
            clientId = CLIENT_ID,
            attestationIssuer = attestationKey.subject,
            attestedKey = keyAttestation.publicKey,
            nonce = null,
            walletName = "Multipaz TestApp",
            walletLink = "https://apps.multipaz.org"
        )

    override suspend fun createJwtKeyAttestation(
        keyAttestations: List<KeyAttestation>,
        challenge: String,
        userAuthentication: List<String>?,
        keyStorage: List<String>?
    ): String = OpenID4VCIBackendUtil.createJwtKeyAttestation(
        signingKey = attestationKey,
        attestationIssuer = attestationKey.subject,
        keysToAttest = keyAttestations,
        challenge = challenge,
        userAuthentication = userAuthentication,
        keyStorage = keyStorage,
    )

    companion object {
        private val clientAssertionJwk = """
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

        private val attestationJwk = """
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

        private val clientAssertionKey = AsymmetricKey.parseExplicit(clientAssertionJwk)
        private val attestationKey = AsymmetricKey.parseExplicit(attestationJwk)
        const val CLIENT_ID = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4"
    }
}