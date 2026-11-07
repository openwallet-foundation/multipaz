package org.multipaz.mdoc.response

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.trustmanagement.ConfigurableTrustManager
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toHex
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Runs the mdoc refusal vectors in [MdocRefusalVectors] through [DeviceResponse.verify].
 *
 * The verifier side is fixed: the SessionTranscript is built here from the request values stored with each vector
 * (OpenID4VP 1.0 B.2.6), the trust anchor is the vectors' IACA, and the time is [MdocRefusalVectors.AT]. A control must
 * verify and its chain must be trusted; the repaired twin in [MdocRefusalVectors.OWN_TWINS] must verify; every refused
 * vector must fail with the exception type, message and stage given in [expectedOutcomes].
 */
class DeviceResponseRefusalVectorsTest {

    private enum class Stage {
        /** [DeviceResponse.fromDataItem] throws. */
        PARSE,

        /** [DeviceResponse.fromDataItem] succeeds and [DeviceResponse.verify] throws. */
        VERIFY
    }

    private class Outcome(val stage: Stage, val type: KClass<out Throwable>, val message: String)

    private val at = Instant.parse(MdocRefusalVectors.AT)

    private val deviceSignatureFailed = Outcome(
        Stage.VERIFY, IllegalStateException::class, "Device authentication signature failed to verify"
    )
    private val issuerSignatureFailed = Outcome(
        Stage.VERIFY, IllegalStateException::class, "Signature on MSO failed to verify"
    )
    private val digestMismatch = Outcome(
        Stage.VERIFY, IllegalStateException::class,
        "Digest mismatch for data element family_name in org.iso.18013.5.1"
    )

    private val expectedOutcomes: Map<String, Outcome> = mapOf(
        "md-st-nonce" to deviceSignatureFailed,
        "md-st-response-uri" to deviceSignatureFailed,
        "md-st-thumbprint-other-key" to deviceSignatureFailed,
        "md-st-thumbprint-absent" to deviceSignatureFailed,
        "md-st-dcapi-handover-on-redirect" to deviceSignatureFailed,
        "md-st-redirect-handover-on-dcapi" to deviceSignatureFailed,
        "md-st-dcapi-origin-prefixed" to deviceSignatureFailed,
        "md-st-dcapi-origin-other" to deviceSignatureFailed,
        "md-devicesig-other-key" to deviceSignatureFailed,
        "md-devicesig-doctype-other" to deviceSignatureFailed,
        "md-devicekey-kty-crv" to Outcome(Stage.PARSE, IllegalArgumentException::class, "Unsupported curve P256"),
        "md-devicesigned-unauthorized-ns" to Outcome(
            Stage.VERIFY, IllegalStateException::class,
            "Device-signed data element 'device_note' in namespace 'org.example.device' is not authorized by MSO"
        ),
        "md-issuerauth-sig-flipped" to issuerSignatureFailed,
        "md-digest-value-modified" to digestMismatch,
        "md-digest-reencoded-item" to digestMismatch,
        "md-digest-length" to digestMismatch,
        "md-digest-id-absent" to Outcome(
            Stage.VERIFY, IllegalStateException::class,
            "No digests ID for data element birth_date in org.iso.18013.5.1"
        ),
        "md-digest-alg-sha1" to Outcome(
            Stage.PARSE, IllegalArgumentException::class, "Unsupported digest algorithm SHA-1"
        ),
        "md-doctype-vs-mso" to Outcome(
            Stage.VERIFY, IllegalStateException::class, "Mismatch between docType in document and MSO"
        ),
    )

    private fun requestOf(vector: RefusalVector) = when (vector.shape) {
        RefusalRequestShape.REDIRECT -> MdocRefusalVectors.REDIRECT_REQUEST
        RefusalRequestShape.DCAPI -> MdocRefusalVectors.DCAPI_REQUEST
    }

    private suspend fun thumbprintOf(request: RefusalRequest): ByteString {
        val jwk = Json.parseToJsonElement(request.encryptionJwk).jsonObject
        return EcPublicKey.fromJwk(jwk).toJwkThumbprint(Algorithm.SHA256)
    }

    private suspend fun handoverInfoDigest(request: RefusalRequest): ByteArray {
        val thumbprint = thumbprintOf(request).toByteArray().toDataItem()
        val handoverInfo = Cbor.encode(
            buildCborArray {
                when (request.shape) {
                    // B.2.6.1: invocation via redirects.
                    RefusalRequestShape.REDIRECT -> {
                        add(request.audience!!)
                        add(request.nonce)
                        add(thumbprint)
                        add(request.responseUri!!)
                    }
                    // B.2.6.2: invocation via the Digital Credentials API.
                    RefusalRequestShape.DCAPI -> {
                        add(request.origin!!)
                        add(request.nonce)
                        add(thumbprint)
                    }
                }
            }
        )
        return Crypto.digest(Algorithm.SHA256, handoverInfo)
    }

    private suspend fun sessionTranscriptOf(request: RefusalRequest): DataItem {
        val handoverName = when (request.shape) {
            RefusalRequestShape.REDIRECT -> "OpenID4VPHandover"
            RefusalRequestShape.DCAPI -> "OpenID4VPDCAPIHandover"
        }
        val digest = handoverInfoDigest(request)
        return buildCborArray {
            add(Simple.NULL) // DeviceEngagementBytes
            add(Simple.NULL) // EReaderKeyBytes
            addCborArray {
                add(handoverName)
                add(digest)
            }
        }
    }

    private fun iacaTrustManager(): TrustManagerInterface = ConfigurableTrustManager(
        identifier = "refusal_vectors_iaca",
        entries = listOf(
            TrustEntryX509Cert(
                identifier = "iaca",
                metadata = TrustMetadata(),
                certificate = X509Cert.fromPem(MdocRefusalVectors.IACA_PEM)
            )
        )
    )

    private suspend fun parse(vector: RefusalVector): DeviceResponse =
        DeviceResponse.fromDataItem(Cbor.decode(vector.response.fromBase64Url()))

    private suspend fun assertVerifies(vector: RefusalVector) {
        val deviceResponse = parse(vector)
        deviceResponse.verify(
            sessionTranscript = sessionTranscriptOf(requestOf(vector)),
            atTime = at
        )
        assertEquals(1, deviceResponse.documents.size, vector.id)
        assertEquals(DrivingLicense.MDL_DOCTYPE, deviceResponse.documents.first().docType, vector.id)
    }

    @Test
    fun requestValuesAreThePinnedOnes() = runTest {
        for (request in listOf(MdocRefusalVectors.REDIRECT_REQUEST, MdocRefusalVectors.DCAPI_REQUEST)) {
            assertEquals(request.thumbprintHex, thumbprintOf(request).toHex(), "${request.shape} thumbprint")
            assertEquals(
                request.handoverInfoDigestHex,
                handoverInfoDigest(request).toHex(),
                "${request.shape} handover info digest"
            )
        }
    }

    @Test
    fun controlsVerify() = runTest {
        for (control in MdocRefusalVectors.CONTROLS) {
            assertVerifies(control)
        }
    }

    @Test
    fun controlsChainToTheIaca() = runTest {
        val trustManager = iacaTrustManager()
        for (control in MdocRefusalVectors.CONTROLS) {
            val deviceResponse = parse(control)
            deviceResponse.verify(sessionTranscript = sessionTranscriptOf(requestOf(control)), atTime = at)
            val trustResult = trustManager.verify(
                chain = deviceResponse.documents.first().issuerCertChain.certificates,
                atTime = at
            )
            assertTrue(trustResult.isTrusted, "${control.id}: ${trustResult.error}")
        }
    }

    @Test
    fun controlsDoNotChainToAnUnrelatedRoot() = runTest {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootName = X500Name.fromName("C=ZZ,CN=Unrelated test root")
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = rootName,
            issuer = rootName,
            validFrom = at - 1.days,
            validUntil = at + 1.days
        ) {
            includeSubjectKeyIdentifier()
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(true, null)
        }
        val trustManager = ConfigurableTrustManager(
            identifier = "unrelated_root",
            entries = listOf(TrustEntryX509Cert("root", TrustMetadata(), rootCert))
        )
        for (control in MdocRefusalVectors.CONTROLS) {
            val deviceResponse = parse(control)
            deviceResponse.verify(sessionTranscript = sessionTranscriptOf(requestOf(control)), atTime = at)
            val trustResult = trustManager.verify(
                chain = deviceResponse.documents.first().issuerCertChain.certificates,
                atTime = at
            )
            assertFalse(trustResult.isTrusted, control.id)
        }
    }

    @Test
    fun repairedTwinsVerify() = runTest {
        assertEquals(listOf("md-devicesigned-unauthorized-ns"), MdocRefusalVectors.OWN_TWINS.map { it.id })
        for (twin in MdocRefusalVectors.OWN_TWINS) {
            assertVerifies(twin)
        }
    }

    @Test
    fun everyRefusedVectorHasAnExpectedOutcome() {
        assertEquals(expectedOutcomes.keys, MdocRefusalVectors.REFUSED.map { it.id }.toSet())
        assertEquals(expectedOutcomes.size, MdocRefusalVectors.REFUSED.size)
    }

    @Test
    fun refusedVectorsAreRefused() = runTest {
        for (vector in MdocRefusalVectors.REFUSED) {
            val expected = expectedOutcomes[vector.id]!!
            val sessionTranscript = sessionTranscriptOf(requestOf(vector))
            val thrown: Throwable = when (expected.stage) {
                Stage.PARSE -> assertFailsWith(expected.type, vector.id) { parse(vector) }
                Stage.VERIFY -> {
                    val deviceResponse = parse(vector)
                    assertFailsWith(expected.type, vector.id) {
                        deviceResponse.verify(sessionTranscript = sessionTranscript, atTime = at)
                    }
                }
            }
            assertEquals(expected.type, thrown::class, "${vector.id}: exception type")
            assertEquals(expected.message, thrown.message, "${vector.id}: message")
        }
    }
}
