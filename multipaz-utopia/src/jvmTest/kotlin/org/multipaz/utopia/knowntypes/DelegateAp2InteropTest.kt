package org.multipaz.utopia.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Runs AP2 delegation through a real OpenID4VP presentation, the way a wallet answers it, and
 * checks the response has the shape a Delegate SD-JWT verifier depends on (draft §5.1.1).
 *
 * Each response is also written to `build/ap2-interop/`, with the issuer key, nonce and audience
 * needed to verify it, so it can be checked against AP2's reference SDK outside this build.
 */
class DelegateAp2InteropTest {

    private val origin = "https://merchant.example.com"

    private fun disclosureOf(mandate: JsonObject): String =
        JsonArray(listOf(JsonPrimitive(Random.nextBytes(16).toBase64Url()), mandate))
            .toString().encodeToByteArray().toBase64Url()

    private fun mandate(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun responsesAreDelegatedSdJwts() = runTest {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build(),
        ) {}
        val repository = DocumentTypeRepository().apply {
            addKnownTypes("en-US")
            addUtopiaTypes("en-US")
            addTransactionType(DelegateTransaction)
        }

        // The payment credential: an SD-JWT VC bound to a key in the wallet's secure area.
        // Multipaz only reads SD-JWT VCs whose issuer key is X.509-certified; AP2 takes the key as is.
        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(issuerPrivateKey),
            subject = X500Name.fromName("C=US,CN=AP2 interop test issuer"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://issuer.example.com",
            crlUrl = "https://issuer.example.com/crl",
        )
        val issuerKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(issuerCert)), issuerPrivateKey)
        val document = documentStore.createDocument(displayName = "Payment card")
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = DigitalPaymentCredentialSdJwt.CARD_VCT,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        )
        val now = Clock.System.now()
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = secureArea.getKeyInfo(credential.alias).publicKey,
            claims = buildJsonObject {
                put("network", "multipaz")
                put("last_four", "1234")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", DigitalPaymentCredentialSdJwt.CARD_VCT)
                put("iat", (now - 1.days).epochSeconds)
                put("exp", (now + 365.days).epochSeconds)
            },
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())

        val source = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = repository,
            showConsentPromptFn = ::promptModelSilentConsent,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
        )

        val agentJwk = Crypto.createEcPrivateKey(EcCurve.P256).publicKey.toJwk(null)
        val exp = (now + 30.days).epochSeconds
        val merchant = """{"id":"merchant.example.com","name":"Utopia Audio","website":"$origin"}"""
        val openCheckout = mandate(
            """{"vct":"mandate.checkout.open.1","exp":$exp,"cnf":{"jwk":$agentJwk},"constraints":[
                 {"type":"checkout.allowed_merchants","allowed":[$merchant]},
                 {"type":"checkout.line_items","items":[
                   {"id":"r1","acceptable_items":[{"id":"aurora","title":"Aurora headphones"}],"quantity":1}]}]}"""
        )
        val openPayment = mandate(
            """{"vct":"mandate.payment.open.1","exp":$exp,"cnf":{"jwk":$agentJwk},"constraints":[
                 {"type":"payment.amount_range","currency":"USD","max":13000},
                 {"type":"payment.budget","currency":"USD","max":200.00},
                 {"type":"payment.allowed_payees","allowed":[$merchant]}]}"""
        )
        val closedCheckout = mandate(
            """{"vct":"mandate.checkout.1","exp":$exp,"constraints":[
                 {"type":"checkout.allowed_merchants","allowed":[$merchant]}]}"""
        )

        // Delegate SD-JWT §7.1 requests: one item per mandate, each with its disclosure.
        val disclosureScenarios = mapOf(
            // The agent may act later: the hop names its key, so the chain continues.
            "open_checkout" to listOf(openCheckout),
            // AP2's usual pair, both naming the agent's key.
            "open_checkout_and_payment" to listOf(openCheckout, openPayment),
            // A mandate without `cnf` listed first: the type must still continue the chain.
            "closed_then_open" to listOf(closedCheckout, openPayment),
            // Nothing names a further key: the hop ends the chain.
            "terminal" to listOf(closedCheckout),
        )
        // AP2 requests: one item with every mandate in the clear in `delegate_payload`.
        val plainScenarios = mapOf(
            "ap2_open_checkout_and_payment" to listOf(openCheckout, openPayment),
            "ap2_closed_then_open" to listOf(closedCheckout, openPayment),
        )

        // An AP2 mandate whose merchant and expiry are selectively disclosable, with their
        // disclosures in `delegate_disclosures` (RFC 9901 §4.2).
        val merchantDisclosure = sdDisclosure(JsonPrimitive(Random.nextBytes(16).toBase64Url()), Json.parseToJsonElement(merchant))
        val expDisclosure = sdDisclosure(JsonPrimitive(Random.nextBytes(16).toBase64Url()), JsonPrimitive("exp"), JsonPrimitive(exp))
        val selectiveCheckout = mandate(
            """{"vct":"mandate.checkout.open.1","cnf":{"jwk":$agentJwk},"_sd":["${digestOf(expDisclosure)}"],"constraints":[
                 {"type":"checkout.allowed_merchants","allowed":[{"...":"${digestOf(merchantDisclosure)}"}]}]}"""
        )
        val selectiveDisclosures = listOf(merchantDisclosure, expDisclosure)

        val outDir = File("build/ap2-interop").apply { mkdirs() }
        val scenarios = disclosureScenarios.mapValues { Triple(it.value, false, emptyList<String>()) } +
            plainScenarios.mapValues { Triple(it.value, true, emptyList<String>()) } +
            ("ap2_selectively_disclosed" to Triple(listOf(selectiveCheckout, openPayment), true, selectiveDisclosures))
        for ((name, scenario) in scenarios) {
            val (mandates, plain, delegateDisclosures) = scenario
            val disclosures = if (plain) null else mandates.map { disclosureOf(it) }
            val nonce = Random.nextBytes(16).toBase64Url()
            val request = OpenID4VP.generateRequest(
                version = OpenID4VP.Version.DRAFT_29,
                origin = origin,
                nonce = nonce,
                responseEncryptionKey = null,
                verifierIdentities = emptyList(),
                responseMode = OpenID4VP.ResponseMode.DC_API,
                responseUri = null,
                dcqlQuery = Json.parseToJsonElement(
                    """{"credentials":[{"id":"payment_card","format":"dc+sd-jwt",
                        "meta":{"vct_values":["${DigitalPaymentCredentialSdJwt.CARD_VCT}"]},
                        "claims":[{"path":["last_four"]}]}]}"""
                ).jsonObject,
                jsonTransactionData = disclosures?.map { disclosure ->
                    buildJsonObject {
                        put("type", "delegate")
                        putJsonArray("credential_ids") { add(JsonPrimitive("payment_card")) }
                        put("format", "dSD-JWT+KB")
                        put("delegate_payload_disclosure", disclosure)
                        putJsonArray("transaction_data_hashes_alg") { add(JsonPrimitive("sha-256")) }
                    }.toString()
                } ?: listOf(
                    buildJsonObject {
                        put("type", "delegate")
                        putJsonArray("credential_ids") { add(JsonPrimitive("payment_card")) }
                        put("format", "dc+sd-jwt")
                        put("delegate_payload", JsonArray(mandates))
                        if (delegateDisclosures.isNotEmpty()) {
                            put("delegate_disclosures", JsonArray(delegateDisclosures.map { JsonPrimitive(it) }))
                        }
                        putJsonArray("transaction_data_hashes_alg") { add(JsonPrimitive("sha-256")) }
                    }.toString()
                ),
            )
            val response = digitalCredentialsPresentment(
                protocol = "openid4vp-v1-unsigned",
                data = request,
                appId = null,
                origin = origin,
                preselectedDocuments = emptyList(),
                source = source,
            )
            val vpToken = response["data"]!!.jsonObject["vp_token"]!!.jsonObject["payment_card"]!!
                .jsonArray.single().jsonPrimitive.content

            File(outDir, "$name.json").writeText(
                buildJsonObject {
                    put("scenario", name)
                    put("vp_token", vpToken)
                    put("issuer_jwk", issuerPrivateKey.publicKey.toJwk(null))
                    put("expected_nonce", nonce)
                    put("expected_aud", "origin:$origin")
                    put("mandate_disclosures", JsonArray(disclosuresAfterKb(vpToken).map { JsonPrimitive(it) }))
                    put("mandates", JsonArray(mandates))
                }.toString()
            )

            val expectedTyp = if (mandates.any { "cnf" in it }) "kb+sd-jwt+kb" else "kb+sd-jwt"
            shapeChecks(vpToken, nonce, "origin:$origin", mandates, disclosures, delegateDisclosures, expectedTyp).forEach { (check, passed) ->
                results += Triple(name, check, passed)
            }
        }

        // A term behind a digest without its disclosure cannot be shown, so it is not signed:
        // the request is refused before the consent prompt.
        val missing = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
            nonce = Random.nextBytes(16).toBase64Url(),
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = Json.parseToJsonElement(
                """{"credentials":[{"id":"payment_card","format":"dc+sd-jwt",
                    "meta":{"vct_values":["${DigitalPaymentCredentialSdJwt.CARD_VCT}"]},
                    "claims":[{"path":["last_four"]}]}]}"""
            ).jsonObject,
            jsonTransactionData = listOf(
                buildJsonObject {
                    put("type", "delegate")
                    putJsonArray("credential_ids") { add(JsonPrimitive("payment_card")) }
                    put("format", "dc+sd-jwt")
                    put("delegate_payload", JsonArray(listOf(selectiveCheckout)))
                    put("delegate_disclosures", JsonArray(listOf(JsonPrimitive(merchantDisclosure))))
                }.toString()
            ),
        )
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            digitalCredentialsPresentment(
                protocol = "openid4vp-v1-unsigned",
                data = missing,
                appId = null,
                origin = origin,
                preselectedDocuments = emptyList(),
                source = source,
            )
        }

        // The shape a Delegate SD-JWT verifier depends on (draft §5.1.1), reported for every
        // scenario before failing.
        println("\n%-28s %-62s %s".format("SCENARIO", "CHECK", "RESULT"))
        results.forEach { (scenario, check, passed) ->
            println("%-28s %-62s %s".format(scenario, check, if (passed) "pass" else "FAIL"))
        }
        val failed = results.filter { !it.third }.map { "${it.first}: ${it.second}" }.distinct()
        assertTrue(failed.isEmpty(), "Failed checks:\n" + failed.joinToString("\n"))
    }

    private val results = mutableListOf<Triple<String, String, Boolean>>()

    private fun decodeJson(part: String) = Json.parseToJsonElement(part.fromBase64Url().decodeToString())

    /** The disclosures after the KB-JWT of a Delegated SD-JWT. */
    private fun disclosuresAfterKb(vpToken: String): List<String> =
        vpToken.substringAfterLast("~~").split('~').drop(1).filter { it.isNotEmpty() }

    /**
     * @param mandates the mandates requested, in order.
     * @param sentDisclosures the disclosures the request carried, or `null` when the wallet made
     *   them (AP2's form).
     * @param delegateDisclosures the `delegate_disclosures` the request carried, which follow the
     *   mandate disclosures.
     */
    private suspend fun shapeChecks(
        vpToken: String,
        nonce: String,
        aud: String,
        mandates: List<JsonObject>,
        sentDisclosures: List<String>?,
        delegateDisclosures: List<String>,
        expectedTyp: String,
    ): List<Pair<String, Boolean>> = buildList {
        val parts = vpToken.split('~')
        val jwtIndexes = parts.indices.filter { parts[it].split('.').size == 3 }
        add("exactly two compact JWTs: issuer and KB-SD-JWT" to (jwtIndexes.size == 2))
        val kbIndex = jwtIndexes.last()
        val kbHeader = decodeJson(parts[kbIndex].substringBefore('.')).jsonObject
        val kbPayload = decodeJson(parts[kbIndex].split('.')[1]).jsonObject

        add("ends with '~' (dSD-JWT, not SD-JWT+KB)" to vpToken.endsWith("~"))
        add("'~~' before the KB-SD-JWT (one empty component)" to (parts.getOrNull(kbIndex - 1) == ""))
        add("typ is $expectedTyp" to (kbHeader["typ"]?.jsonPrimitive?.content == expectedTyp))
        add("nonce, aud and iat present and right" to (
            kbPayload["nonce"]?.jsonPrimitive?.content == nonce &&
                kbPayload["aud"]?.jsonPrimitive?.content == aud && kbPayload["iat"] != null))
        add("_sd_alg is sha-256" to (kbPayload["_sd_alg"]?.jsonPrimitive?.content == "sha-256"))

        val sdJwtPart = parts.subList(0, kbIndex).filter { it.isNotEmpty() }.joinToString("~", postfix = "~")
        val sdHash = Crypto.digest(Algorithm.SHA256, sdJwtPart.encodeToByteArray()).toBase64Url()
        add("sd_hash covers the issuer JWT and its disclosures" to (kbPayload["sd_hash"]?.jsonPrimitive?.content == sdHash))

        val digests = kbPayload["delegate_payload"]?.jsonArray
            ?.map { it.jsonObject["..."]?.jsonPrimitive?.content }.orEmpty()
        add("delegate_payload: one {\"...\": digest} per mandate" to
            (digests.size == mandates.size && digests.all { it != null }))

        val afterKb = parts.drop(kbIndex + 1).filter { it.isNotEmpty() }
        val mandateDisclosures = afterKb.take(mandates.size)
        add("mandate disclosures follow the KB-SD-JWT, in order" to (afterKb.size == mandates.size + delegateDisclosures.size &&
            mandateDisclosures.zip(mandates).all { (disclosure, mandate) ->
                (decodeJson(disclosure) as? JsonArray)?.let { a -> a.size == 2 && a[1] == mandate } == true
            } && (sentDisclosures == null || mandateDisclosures == sentDisclosures)))
        add("delegate_disclosures follow the mandate disclosures" to (afterKb.drop(mandates.size) == delegateDisclosures))
        add("each digest matches a disclosure after the KB-SD-JWT" to (mandateDisclosures.isNotEmpty() &&
            digests == mandateDisclosures.map { Crypto.digest(Algorithm.SHA256, it.encodeToByteArray()).toBase64Url() }))
    }

    private fun sdDisclosure(vararg members: JsonElement): String =
        JsonArray(members.toList()).toString().encodeToByteArray().toBase64Url()

    private suspend fun digestOf(disclosure: String): String =
        Crypto.digest(Algorithm.SHA256, disclosure.encodeToByteArray()).toBase64Url()
}
