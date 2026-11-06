package org.multipaz.verifier.request

import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class VerifierTest {
    @Test
    fun rawDcqlDraft24UnsignedAndOfferedProtocolEnforcement() = runBlocking {
        val now = Clock.System.now()
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
            crlUrl = "https://verifier.example.com/crl"
        )
        val readerKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootKey
            ),
            readerKey = readerKeyPrivate.publicKey,
            subject = X500Name.fromName("CN=verifier.example.com"),
            dnsName = "verifier.example.com",
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
            extensions = emptyList()
        )
        val readerAuthKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerCert, readerRootCert)),
            privateKey = readerKeyPrivate
        )

        val rawDcql = """
            {
              "credentials": [
                {
                  "id": "pid",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["${EUPersonalID.EUPID_VCT}"]
                  },
                  "claims": [
                    { "path": ["given_name"] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val nonce = ByteString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))
        val origin = "https://verifier.example.com"
        val host = "verifier.example.com"
        val session = Session(
            requestFormat = "vc",
            requestDocType = EUPersonalID.EUPID_VCT,
            requestId = "",
            rawDcql = rawDcql,
            multiDocumentRequestId = "",
            protocol = Protocol.W3C_DC_OPENID4VP_24,
            nonce = nonce,
            origin = origin,
            host = host,
            encryptionKey = encryptionKey,
            signRequest = false,
            encryptResponse = false,
        )
        val repo = DocumentTypeRepository().apply { addKnownTypes() }
        val beginResponse = calcDcRequestNewRawDcql(
            rawDcql = rawDcql,
            exchangeProtocols = listOf("openid4vp"),
            sessionId = "session-1",
            documentTypeRepository = repo,
            session = session,
            nonce = nonce,
            origin = origin,
            readerKey = encryptionKey,
            readerPublicKey = encryptionKey.publicKey as EcPublicKeyDoubleCoordinate,
            readerAuthKey = readerAuthKey,
            signRequest = false,
            encryptResponse = false,
        )
        assertEquals(listOf("openid4vp"), session.offeredDcProtocols)
        val requestJson = Json.decodeFromString<JsonObject>(beginResponse.dcRequestString)
        // Unsigned Draft 24 request has no "client_id" or signed "request" JWT in the DC request payload
        assertEquals(null, requestJson["client_id"])
        assertEquals(null, requestJson["request"])

        // Rejects a protocol that was not offered by the session
        try {
            processDcGetData(
                session = session,
                credentialProtocol = "openid4vp-v1-unsigned",
                credentialResponse = "{}"
            )
            fail("Expected IllegalArgumentException for unoffered protocol")
        } catch (e: IllegalArgumentException) {
            assertEquals("Credential protocol 'openid4vp-v1-unsigned' was not offered in session", e.message)
        }

        // Verify that a presentation with audience "web-origin:https://verifier.example.com" succeeds
        val issuerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(issuerKey),
            subject = X500Name.fromName("CN=Test Issuer"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
            crlUrl = "https://issuer.example.com/crl"
        )
        val holderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(issuerCert)), issuerKey),
            kbKey = holderKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (now - 1.days).epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            }
        )
        val sdJwtKb = sdJwt.present(
            signingKey = AsymmetricKey.AnonymousExplicit(holderKey),
            nonce = nonce.toByteArray().toBase64Url(),
            audience = "web-origin:$origin",
            creationTime = now,
        )
        val credentialResponse = buildJsonObject {
            putJsonObject("vp_token") {
                put("pid", buildJsonArray { add(JsonPrimitive(sdJwtKb.compactSerialization)) })
            }
        }.toString()

        val pages = processDcGetData(
            session = session,
            credentialProtocol = "openid4vp",
            credentialResponse = credentialResponse
        )
        assertEquals(1, pages.size)
        assertTrue(pages[0].lines.contains(ResultLine("Key Binding", "Verified")))
    }
}
