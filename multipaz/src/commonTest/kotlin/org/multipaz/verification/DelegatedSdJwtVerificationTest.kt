package org.multipaz.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.toBase64Url

/**
 * The verifier side of a Delegated SD-JWT, `<SD-JWT>~~<KB-JWT>~<Disclosure>~`, as
 * [SdJwt.presentWithKbDisclosures] produces it for a transaction type that returns disclosures.
 */
class DelegatedSdJwtVerificationTest {

    private val nonce = "nonce"
    private val audience = "https://verifier.example.org"
    private val disclosure = """["salt",{"delegated":"payload"}]""".encodeToByteArray().toBase64Url()

    /** Asks for its own Key Binding JWT type and keeps what its response check was given. */
    private class DelegateType(private val takesDisclosures: Boolean) : TransactionType<String>(
        displayName = "Delegate",
        identifier = "delegate",
        nestSdJwtResponseClaims = false,
    ) {
        var responseClaims: Map<String, JsonElement>? = null
        var kbDisclosures: List<String>? = null

        override fun sdJwtKbType(transactionData: List<TransactionData<String>>) = "kb+sd-jwt"

        override suspend fun verifySdJwtResponse(
            transactionData: TransactionData<String>,
            responseClaims: Map<String, JsonElement>,
            kbDisclosures: List<String>
        ) {
            if (!takesDisclosures) {
                super.verifySdJwtResponse(transactionData, responseClaims, kbDisclosures)
            }
            this.responseClaims = responseClaims
            this.kbDisclosures = kbDisclosures
        }
    }

    private class Issued(val sdJwt: SdJwt, val kbKey: EcPrivateKey)

    private suspend fun issue(): Issued {
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val validFrom = Clock.System.now() - 1.days
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey),
            subject = X500Name.fromName("CN=Test IACA,C=US"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validFrom + 365.days,
            issuerAltNameUrl = "https://issuer.example.com",
            crlUrl = "https://issuer.example.com/crl",
        )
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = dsKey.publicKey,
            subject = X500Name.fromName("CN=Test DS,C=US"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validFrom + 365.days,
        )
        val kbKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert, iacaCert)), dsKey),
            kbKey = kbKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "urn:example:1")
            },
        )
        return Issued(sdJwt, kbKey)
    }

    private fun transactionData(type: TransactionType<String>) = TransactionData(
        type = type,
        payload = "payload",
        protocol = TransactionProtocol.OPENID4VP,
        rawBytes = ByteString("""{"type":"delegate"}""".encodeToByteArray()),
    )

    /** What a wallet returns for [transactionData]: its hash and a claim that commits to [disclosure]. */
    private suspend fun delegated(
        issued: Issued,
        transactionData: TransactionData<String>,
        typ: String = "kb+sd-jwt",
    ): String {
        val transactionDataHash = transactionData.computeHash(Algorithm.SHA256).toByteArray().toBase64Url()
        val disclosureDigest = Crypto.digest(Algorithm.SHA256, disclosure.encodeToByteArray()).toBase64Url()
        return issued.sdJwt.presentWithKbDisclosures(
            signingKey = AsymmetricKey.anonymous(issued.kbKey),
            nonce = nonce,
            audience = audience,
            kbDisclosures = listOf(disclosure),
            type = typ,
        ) {
            putJsonArray("transaction_data_hashes") { add(transactionDataHash) }
            putJsonArray("delegate_payload") { add(disclosureDigest) }
        }
    }

    private suspend fun verify(serialization: String, transactionData: List<TransactionData<*>>) =
        VerificationUtil.verifySdJwtPresentation(
            compactSerialization = serialization,
            nonce = nonce,
            documentTypeRepository = null,
            transactionData = transactionData,
            expectedAudience = audience,
        )

    @Test
    fun verifiesDelegatedSdJwtAndHandsDisclosuresAndClaimsToTheType() = runTest {
        val issued = issue()
        val type = DelegateType(takesDisclosures = true)
        val data = transactionData(type)

        val presentation = verify(delegated(issued, data), listOf(data)) as JsonVerifiedPresentation

        assertEquals(listOf(disclosure), type.kbDisclosures)
        // The claims beside the Key Binding JWT's own, not the whole body.
        assertEquals(setOf("delegate_payload"), type.responseClaims!!.keys)
        assertEquals(1, type.responseClaims!!["delegate_payload"]!!.jsonArray.size)
        val response = presentation.transactionResponses!!["delegate"]!!
        assertTrue(response is JsonObject && "delegate_payload" in response)
        assertTrue(presentation.keyBound)
    }

    @Test
    fun refusesDisclosuresForATypeThatDoesNotTakeThem() = runTest {
        val issued = issue()
        val type = DelegateType(takesDisclosures = false)
        val data = transactionData(type)

        val error = assertFailsWith<IllegalStateException> { verify(delegated(issued, data), listOf(data)) }
        assertTrue("does not take disclosures" in error.message!!, error.message)
    }

    @Test
    fun refusesAKeyBindingJwtTypeOtherThanTheOneTheRequestAsksFor() = runTest {
        val issued = issue()
        val data = transactionData(DelegateType(takesDisclosures = true))

        val error = assertFailsWith<IllegalStateException> {
            verify(delegated(issued, data, typ = "kb-sd-jwt+kb"), listOf(data))
        }
        assertTrue("must be 'kb+sd-jwt'" in error.message!!, error.message)
    }

    @Test
    fun refusesDisclosuresWhenNoTransactionTypeAsksForAKeyBindingJwtType() = runTest {
        val issued = issue()
        val data = transactionData(DelegateType(takesDisclosures = true))
        val serialization = delegated(issued, data)

        // The request carried no transaction data, so only the ordinary `kb+jwt` is acceptable.
        val error = assertFailsWith<IllegalStateException> { verify(serialization, emptyList()) }
        assertTrue("need a transaction type that asks for its own" in error.message!!, error.message)
    }

    @Test
    fun parsesDelegatedSerializationIntoKeyBindingSdJwtAndDisclosures() = runTest {
        val issued = issue()
        val serialization = delegated(issued, transactionData(DelegateType(takesDisclosures = true)))

        val sdJwtKb = SdJwtKb.fromDelegatedCompactSerialization(serialization)

        assertEquals(listOf(disclosure), sdJwtKb.kbDisclosures)
        assertEquals(issued.sdJwt.compactSerialization, sdJwtKb.sdJwt.compactSerialization)
        assertNotNull(sdJwtKb.jwtBody["sd_hash"])
        assertEquals("kb+sd-jwt", sdJwtKb.jwtHeader["typ"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusesMalformedDelegatedSerializations() = runTest {
        val issued = issue()
        val serialization = delegated(issued, transactionData(DelegateType(takesDisclosures = true)))
        val sdJwt = issued.sdJwt.compactSerialization
        val kbJwt = serialization.removePrefix("$sdJwt~").removeSuffix("~$disclosure~")

        for ((case, malformed) in mapOf(
            "no '~~'" to "$sdJwt$kbJwt",
            "two '~~'" to "$serialization~$kbJwt~$disclosure~",
            "no disclosure" to "$sdJwt~$kbJwt~",
            "no trailing '~'" to "$sdJwt~$kbJwt~$disclosure",
        )) {
            assertFailsWith<IllegalArgumentException>(case) {
                SdJwtKb.fromDelegatedCompactSerialization(malformed)
            }
        }
    }
}
