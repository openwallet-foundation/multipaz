package org.multipaz.openid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.ISO_23220_5_CHV_1_DATA_ELEMENT
import org.multipaz.documenttype.ISO_23220_5_CHV_1_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.mdoc.request.AlternativeDataElementSet
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.ElementReference
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.mdocPresentment
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.RequestedClaim
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.util.zlibInflate
import org.multipaz.verification.VerifierIdentity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OpenID4VPClaimSetsTest {

    companion object {
        private const val ORIGIN = "https://verifier.invalid"
        private const val RESPONSE_URI = "https://verifier.invalid/callback"
        private const val CLIENT_ID = "x509_san_dns:verifier.invalid"
        private const val DNS_NAME = "verifier.invalid"
        private const val PID_VCT = EUPersonalID.EUPID_VCT
    }

    private class SourceWithConsent(
        val source: PresentmentSource,
        val recordedConsentData: MutableList<PresentmentData>,
    )

    private class TestFaceMatcherSession(referencePortrait: ByteString) : FaceMatcherSession(referencePortrait) {
        override suspend fun feedFrame(frame: CameraFrame) {}
    }

    private class TestFaceMatcher : FaceMatcher {
        override val name = "test"
        override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
            TestFaceMatcherSession(referencePortrait)
    }

    private class TestPromptModel private constructor(builder: Builder) : PromptModel(builder) {
        override val promptModelScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

        class Builder : PromptModel.Builder(
            toHumanReadable = { _, _ -> throw IllegalStateException("unexpected state") }
        ) {
            override fun build(): TestPromptModel = TestPromptModel(this)
        }
    }

    private fun TestScope.setupDialogMock(
        promptModel: TestPromptModel,
        mockInput: suspend (request: FaceMatcherPromptDialogModel.FaceMatcherRequest) -> Boolean
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            var pendingResultChannel: SendChannel<Boolean>? = null
            try {
                val dialogModel = promptModel.getDialogModel(FaceMatcherPromptDialogModel.DialogType)
                dialogModel.dialogState.collect { state ->
                    pendingResultChannel = null
                    if (state is PromptDialogModel.DialogShownState) {
                        try {
                            val result = mockInput(state.parameters)
                            state.resultChannel.send(result)
                        } catch (e: PromptDismissedException) {
                            state.resultChannel.close(e)
                        }
                    }
                }
            } catch (err: CancellationException) {
                pendingResultChannel?.close(PromptDismissedException())
                throw err
            }
        }
    }

    private fun createSource(
        harness: DocumentStoreTestHarness,
        zkSystemRepository: ZkSystemRepository? = null,
        faceMatcher: FaceMatcher? = null,
        faceMatchingMode: FaceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED,
    ): SourceWithConsent {
        val recordedConsentData = mutableListOf<PresentmentData>()
        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            zkSystemRepository = zkSystemRepository,
            getFaceMatcherFn = { faceMatcher },
            getFaceMatchingModeFn = { _, _ -> faceMatchingMode },
            showConsentPromptFn = { requester, trustedIdentity, data, preselected, onDocsInFocus ->
                recordedConsentData.add(data)
                promptModelSilentConsent(requester, trustedIdentity, data, preselected, onDocsInFocus)
            },
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeylessMdoc = listOf("mdoc_keyless"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
            domainsKeylessSdJwt = listOf("sdjwt_keyless"),
        )
        return SourceWithConsent(source, recordedConsentData)
    }

    private suspend fun provisionPidSdJwtVc(
        harness: DocumentStoreTestHarness,
        includeAgeOver18: Boolean = true,
        keyless: Boolean = false,
    ) {
        val data = buildList {
            add("given_name" to JsonPrimitive("Erika"))
            add("family_name" to JsonPrimitive("Mustermann"))
            add("birthdate" to JsonPrimitive("1971-09-01"))
            if (includeAgeOver18) {
                add("age_over_18" to JsonPrimitive(true))
            }
            add("personal_administrative_number" to JsonPrimitive("123456789"))
            add(
                "address" to buildJsonObject {
                    put("country", JsonPrimitive("DE"))
                    put("locality", JsonPrimitive("Berlin"))
                    put("postal_code", JsonPrimitive("10115"))
                    put("street_address", JsonPrimitive("Heidestraße 17"))
                }
            )
        }
        if (keyless) {
            harness.provisionKeylessSdJwtVc(
                displayName = "Keyless PID",
                vct = PID_VCT,
                data = data,
            )
        } else {
            harness.provisionSdJwtVc(
                displayName = "KeyBound PID",
                vct = PID_VCT,
                data = data,
            )
        }
    }

    /**
     * Decodes disclosures directly from a compact SD-JWT / SD-JWT+KB serialization using raw
     * Base64URL and JSON parsing, independent of Multipaz verification APIs.
     */
    private fun decodeRawDisclosures(compactSerialization: String): List<JsonArray> {
        val parts = compactSerialization.split("~")
        // Parts[0] is issuer JWT; parts[parts.size - 1] is either "" (SdJwt) or KB-JWT (SdJwtKb).
        return parts.subList(1, parts.size - 1).map { encoded ->
            Json.parseToJsonElement(encoded.fromBase64Url().decodeToString()).jsonArray
        }
    }

    private fun extractConsentJsonClaimPaths(consentData: PresentmentData): List<List<String>> {
        val match = consentData.useCases.single().solutions.single().matches.single()
        return match.claims.keys
            .filterIsInstance<JsonRequestedClaim>()
            .map { req -> req.claimPath.map { it.jsonPrimitive.content } }
    }

    private fun extractEventJsonClaimPaths(response: OpenID4VP.OpenID4VPResponse): List<List<String>> {
        val doc = response.eventData.requestedDocuments.single()
        return doc.claims.keys
            .filterIsInstance<JsonRequestedClaim>()
            .map { req -> req.claimPath.map { it.jsonPrimitive.content } }
    }

    private fun extractConsentMdocElements(consentData: PresentmentData): List<Pair<String, String>> {
        val match = consentData.useCases.single().solutions.single().matches.single()
        return match.claims.keys
            .filterIsInstance<MdocRequestedClaim>()
            .map { it.namespaceName to it.dataElementName }
    }

    private fun extractEventMdocElements(response: OpenID4VP.OpenID4VPResponse): List<Pair<String, String>> {
        val doc = response.eventData.requestedDocuments.single()
        return doc.claims.keys
            .filterIsInstance<MdocRequestedClaim>()
            .map { it.namespaceName to it.dataElementName }
    }

    private suspend fun verifyKeyBoundSdJwtToken(
        harness: DocumentStoreTestHarness,
        token: String,
        expectedNonce: String,
        expectedAudience: String,
    ): JsonObject {
        val sdJwtKb = SdJwtKb.fromCompactSerialization(token)
        return sdJwtKb.verify(
            issuerKey = harness.dsKey.publicKey,
            checkNonce = { it == expectedNonce },
            checkAudience = { it == expectedAudience },
            checkCreationTime = { true },
        )
    }

    private suspend fun runSdJwtClaimSetsCase(
        harness: DocumentStoreTestHarness,
        dcqlJson: String,
        version: OpenID4VP.Version = OpenID4VP.Version.DRAFT_29,
        responseMode: OpenID4VP.ResponseMode = OpenID4VP.ResponseMode.DC_API,
        signRequest: Boolean = false,
        encryptResponse: Boolean = false,
        keyless: Boolean = false,
    ): Triple<List<List<String>>, List<List<String>>, String> {
        val sourceWithConsent = createSource(harness)
        val dcql = Json.parseToJsonElement(dcqlJson).jsonObject
        val nonce = "cG9jLW5vbmNlLTAxMjM0NTY3ODk"
        val encKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        val verifierIdentities = if (signRequest) {
            val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCerts = harness.readerRootKey.certChain.certificates
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = harness.readerRootKey,
                readerKey = readerKey.publicKey,
                subject = X500Name.fromName("CN=Multipaz Test Reader"),
                dnsName = DNS_NAME,
                serial = ASN1Integer.fromRandom(128),
                validFrom = rootCerts.first().validityNotBefore,
                validUntil = rootCerts.first().validityNotAfter,
            )
            listOf(
                VerifierIdentity(
                    key = AsymmetricKey.X509CertifiedExplicit(
                        certChain = X509CertChain(listOf(cert) + rootCerts),
                        privateKey = readerKey,
                    ),
                    clientId = CLIENT_ID,
                )
            )
        } else {
            emptyList()
        }
        val responseUri = if (responseMode == OpenID4VP.ResponseMode.DIRECT_POST) RESPONSE_URI else null
        val generated = OpenID4VP.generateRequest(
            version = version,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = encKey?.publicKey,
            verifierIdentities = verifierIdentities,
            responseMode = responseMode,
            responseUri = responseUri,
            dcqlQuery = dcql,
        )
        val (requestObj, requesterIdentities) = if (signRequest) {
            val jwt = generated["request"]!!.jsonPrimitive.content
            val info = JsonWebSignature.getInfo(jwt)
            val id = OpenID4VPRequesterIdentity.fromSignedRequest(info.x5c!!, CLIENT_ID)
            info.claimsSet to listOf(id)
        } else {
            generated to emptyList()
        }

        val response = OpenID4VP.generateResponse(
            version = version,
            preselectedDocuments = emptyList(),
            source = sourceWithConsent.source,
            appId = null,
            origin = ORIGIN,
            request = requestObj,
            requesterIdentities = requesterIdentities,
        )

        val vpTokenContainer = if (encryptResponse) {
            JsonWebEncryption.decrypt(
                response.response["response"]!!.jsonPrimitive.content,
                AsymmetricKey.anonymous(encKey!!, encKey.curve.defaultKeyAgreementAlgorithm)
            )
        } else {
            response.response
        }
        val vpTokenMap = vpTokenContainer["vp_token"]!!.jsonObject
        val compactToken = when (version) {
            OpenID4VP.Version.DRAFT_29 -> vpTokenMap["pid"]!!.jsonArray[0].jsonPrimitive.content
            OpenID4VP.Version.DRAFT_24 -> vpTokenMap["pid"]!!.jsonPrimitive.content
        }

        val expectedAudience = when {
            version == OpenID4VP.Version.DRAFT_29 && responseMode == OpenID4VP.ResponseMode.DC_API -> "origin:$ORIGIN"
            signRequest -> CLIENT_ID
            else -> "web-origin:$ORIGIN"
        }
        if (keyless) {
            val sdJwt = SdJwt.fromCompactSerialization(compactToken)
            assertNull(sdJwt.kbKey)
            sdJwt.verify(harness.dsKey.publicKey)
        } else {
            verifyKeyBoundSdJwtToken(harness, compactToken, nonce, expectedAudience)
        }

        val consentPaths = extractConsentJsonClaimPaths(sourceWithConsent.recordedConsentData.single())
        val eventPaths = extractEventJsonClaimPaths(response)
        return Triple(consentPaths, eventPaths, compactToken)
    }

    @Test
    fun sdJwtVc_control_singleClaimWithoutClaimSets() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionPidSdJwtVc(harness, includeAgeOver18 = true)

        val (consentPaths, eventPaths, token) = runSdJwtClaimSetsCase(
            harness = harness,
            dcqlJson = """
                {
                  "credentials": [{
                    "id": "pid",
                    "format": "dc+sd-jwt",
                    "meta": {"vct_values": ["$PID_VCT"]},
                    "claims": [
                      {"id": "d", "path": ["age_over_18"]}
                    ]
                  }]
                }
            """.trimIndent(),
        )
        assertEquals(listOf(listOf("age_over_18")), consentPaths)
        assertEquals(listOf(listOf("age_over_18")), eventPaths)
        val rawDisclosures = decodeRawDisclosures(token)
        assertEquals(listOf("age_over_18"), rawDisclosures.map { it[1].jsonPrimitive.content })
    }

    @Test
    fun sdJwtVc_singleClaimSet_disclosesOnlySelectedClaimSetAcrossModes() = runTest {
        val dcqlWithSingleClaimSet = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": {"vct_values": ["$PID_VCT"]},
                "claims": [
                  {"id": "a", "path": ["given_name"]},
                  {"id": "b", "path": ["family_name"]},
                  {"id": "c", "path": ["birthdate"]},
                  {"id": "d", "path": ["age_over_18"]}
                ],
                "claim_sets": [["d"]]
              }]
            }
        """.trimIndent()

        for (version in listOf(OpenID4VP.Version.DRAFT_29, OpenID4VP.Version.DRAFT_24)) {
            for (encryptResponse in listOf(false, true)) {
                val harness = DocumentStoreTestHarness()
                harness.initialize()
                provisionPidSdJwtVc(harness, includeAgeOver18 = true)

                val (consentPaths, eventPaths, token) = runSdJwtClaimSetsCase(
                    harness = harness,
                    dcqlJson = dcqlWithSingleClaimSet,
                    version = version,
                    responseMode = OpenID4VP.ResponseMode.DC_API,
                    signRequest = false,
                    encryptResponse = encryptResponse,
                )
                assertEquals(listOf(listOf("age_over_18")), consentPaths)
                assertEquals(listOf(listOf("age_over_18")), eventPaths)
                val rawDisclosures = decodeRawDisclosures(token)
                assertEquals(listOf("age_over_18"), rawDisclosures.map { it[1].jsonPrimitive.content })
                assertEquals(true, rawDisclosures.single()[2].jsonPrimitive.boolean)
            }
        }

        // Also verify signed direct_post and keyless SD-JWT VC with require_cryptographic_holder_binding=false
        val signedHarness = DocumentStoreTestHarness()
        signedHarness.initialize()
        provisionPidSdJwtVc(signedHarness, includeAgeOver18 = true)
        val (signedConsent, signedEvent, signedToken) = runSdJwtClaimSetsCase(
            harness = signedHarness,
            dcqlJson = dcqlWithSingleClaimSet,
            version = OpenID4VP.Version.DRAFT_29,
            responseMode = OpenID4VP.ResponseMode.DIRECT_POST,
            signRequest = true,
            encryptResponse = false,
        )
        assertEquals(listOf(listOf("age_over_18")), signedConsent)
        assertEquals(listOf(listOf("age_over_18")), signedEvent)
        assertEquals(
            listOf("age_over_18"),
            decodeRawDisclosures(signedToken).map { it[1].jsonPrimitive.content }
        )

        val keylessHarness = DocumentStoreTestHarness()
        keylessHarness.initialize()
        provisionPidSdJwtVc(keylessHarness, includeAgeOver18 = true, keyless = true)
        val keylessDcql = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "require_cryptographic_holder_binding": false,
                "meta": {"vct_values": ["$PID_VCT"]},
                "claims": [
                  {"id": "a", "path": ["given_name"]},
                  {"id": "b", "path": ["family_name"]},
                  {"id": "c", "path": ["birthdate"]},
                  {"id": "d", "path": ["age_over_18"]}
                ],
                "claim_sets": [["d"]]
              }]
            }
        """.trimIndent()
        val (keylessConsent, keylessEvent, keylessToken) = runSdJwtClaimSetsCase(
            harness = keylessHarness,
            dcqlJson = keylessDcql,
            keyless = true,
        )
        assertEquals(listOf(listOf("age_over_18")), keylessConsent)
        assertEquals(listOf(listOf("age_over_18")), keylessEvent)
        assertEquals(
            listOf("age_over_18"),
            decodeRawDisclosures(keylessToken).map { it[1].jsonPrimitive.content }
        )
    }

    @Test
    fun sdJwtVc_preferredAndFallbackClaimSets_discloseOnlySelectedSet() = runTest {
        val dcqlWithFallback = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": {"vct_values": ["$PID_VCT"]},
                "claims": [
                  {"id": "a", "path": ["given_name"]},
                  {"id": "b", "path": ["family_name"]},
                  {"id": "c", "path": ["birthdate"]},
                  {"id": "d", "path": ["age_over_18"]}
                ],
                "claim_sets": [["d"], ["a", "b", "c"]]
              }]
            }
        """.trimIndent()

        // Preferred set ["d"] matches: a, b, c must NOT be disclosed
        val harnessPreferred = DocumentStoreTestHarness()
        harnessPreferred.initialize()
        provisionPidSdJwtVc(harnessPreferred, includeAgeOver18 = true)
        val (prefConsent, prefEvent, prefToken) = runSdJwtClaimSetsCase(
            harness = harnessPreferred,
            dcqlJson = dcqlWithFallback,
        )
        assertEquals(listOf(listOf("age_over_18")), prefConsent)
        assertEquals(listOf(listOf("age_over_18")), prefEvent)
        val prefDisclosures = decodeRawDisclosures(prefToken)
        assertEquals(listOf("age_over_18"), prefDisclosures.map { it[1].jsonPrimitive.content })

        // Fallback set ["a", "b", "c"] matches when age_over_18 is missing
        val harnessFallback = DocumentStoreTestHarness()
        harnessFallback.initialize()
        provisionPidSdJwtVc(harnessFallback, includeAgeOver18 = false)
        val (fbConsent, fbEvent, fbToken) = runSdJwtClaimSetsCase(
            harness = harnessFallback,
            dcqlJson = dcqlWithFallback,
        )
        val expectedFallback = listOf(listOf("given_name"), listOf("family_name"), listOf("birthdate"))
        assertEquals(expectedFallback, fbConsent)
        assertEquals(expectedFallback, fbEvent)
        val fbDisclosures = decodeRawDisclosures(fbToken).map { it[1].jsonPrimitive.content }.toSet()
        assertEquals(setOf("given_name", "family_name", "birthdate"), fbDisclosures)
    }

    @Test
    fun sdJwtVc_nestedClaimSets_discloseOnlySelectedNestedClaim() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionPidSdJwtVc(harness, includeAgeOver18 = true)

        val dcqlNested = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": {"vct_values": ["$PID_VCT"]},
                "claims": [
                  {"id": "country", "path": ["address", "country"]},
                  {"id": "postal", "path": ["address", "postal_code"]},
                  {"id": "street", "path": ["address", "street_address"]}
                ],
                "claim_sets": [
                  ["country"],
                  ["street", "postal", "country"]
                ]
              }]
            }
        """.trimIndent()

        val (consentPaths, eventPaths, token) = runSdJwtClaimSetsCase(
            harness = harness,
            dcqlJson = dcqlNested,
        )
        assertEquals(listOf(listOf("address", "country")), consentPaths)
        assertEquals(listOf(listOf("address", "country")), eventPaths)
        val disclosedNames = decodeRawDisclosures(token).map { it[1].jsonPrimitive.content }.toSet()
        assertEquals(setOf("address", "country"), disclosedNames)
        val verifiedPayload = verifyKeyBoundSdJwtToken(harness, token, "cG9jLW5vbmNlLTAxMjM0NTY3ODk", "origin:$ORIGIN")
        assertEquals(setOf("country"), verifiedPayload["address"]!!.jsonObject.keys)
    }

    @Test
    fun mdoc_claimSets_discloseOnlySelectedClaimsInResponseConsentAndEvent() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "family_name" to Tstr("Mustermann"),
                    "birth_date" to Tstr("1971-09-01"),
                    "age_over_18" to true.toDataItem(),
                )
            ),
        )

        val dcql = Json.parseToJsonElement(
            """
                {
                  "credentials": [{
                    "id": "mdl",
                    "format": "mso_mdoc",
                    "meta": {"doctype_value": "${DrivingLicense.MDL_DOCTYPE}"},
                    "claims": [
                      {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                      {"id": "b", "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"]},
                      {"id": "c", "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"]},
                      {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]}
                    ],
                    "claim_sets": [["d"], ["a", "b", "c"]]
                  }]
                }
            """.trimIndent()
        ).jsonObject

        val sourceWithConsent = createSource(harness)
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
        )
        val response = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = sourceWithConsent.source,
            appId = null,
            origin = ORIGIN,
            request = request,
            requesterIdentities = emptyList(),
        )
        val expectedElement = listOf(DrivingLicense.MDL_NAMESPACE to "age_over_18")
        assertEquals(expectedElement, extractConsentMdocElements(sourceWithConsent.recordedConsentData.single()))
        assertEquals(expectedElement, extractEventMdocElements(response))

        val encodedDeviceResponse = response.vpToken["vp_token"]!!.jsonObject["mdl"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val handoverInfoDigest = Crypto.digest(
            Algorithm.SHA256,
            Cbor.encode(buildCborArray { add(ORIGIN); add(nonce); add(Simple.NULL) })
        )
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray { add("OpenID4VPDCAPIHandover"); add(handoverInfoDigest) }
        }
        val dr = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        dr.verifySingleDoc(sessionTranscript, transactionData = emptyList())
        val returnedElements = dr.documents.single().issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.keys
        assertEquals(setOf("age_over_18"), returnedElements)
    }

    @Test
    fun mdocZk_claimSets_selectsZkSpecUsingSelectedClaimSet() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "family_name" to Tstr("Mustermann"),
                    "birth_date" to Tstr("1971-09-01"),
                    "age_over_18" to true.toDataItem(),
                )
            ),
        )

        val seenMatchingClaims = mutableListOf<List<RequestedClaim>>()
        val seenProofElementCounts = mutableListOf<Int>()
        val spec1 = ZkSystemSpec(id = "mock_1_attr", system = "mock_zk").apply {
            addParam("num_attributes", 1)
        }
        val spec3 = ZkSystemSpec(id = "mock_3_attr", system = "mock_zk").apply {
            addParam("num_attributes", 3)
        }
        val mockZkSystem = object : ZkSystem {
            override val name = "mock_zk"
            override val systemSpecs = listOf(spec1, spec3)

            override fun generateProof(
                zkSystemSpec: ZkSystemSpec,
                document: MdocDocument,
                sessionTranscript: DataItem,
                timestamp: Instant,
            ): ZkDocument {
                val count = document.issuerNamespaces.data.values.sumOf { it.size }
                seenProofElementCounts.add(count)
                return ZkDocument(
                    documentData = ZkDocumentData(
                        zkSystemSpecId = zkSystemSpec.id,
                        docType = document.docType,
                        timestamp = timestamp.truncateToWholeSeconds(),
                        issuerSigned = document.issuerNamespaces.data.mapValues { (_, items) ->
                            items.mapValues { (_, item) -> item.dataElementValue }
                        },
                        deviceSigned = emptyMap(),
                        msoX5chain = document.issuerCertChain,
                    ),
                    proof = ByteString(1, 2, 3),
                )
            }

            override fun verifyProof(
                zkDocument: ZkDocument,
                zkSystemSpec: ZkSystemSpec,
                sessionTranscript: DataItem,
                atTime: Instant,
                maxTimestampDrift: Duration,
            ) {}

            override fun getMatchingSystemSpec(
                zkSystemSpecs: List<ZkSystemSpec>,
                requestedClaims: List<RequestedClaim>,
            ): ZkSystemSpec? {
                seenMatchingClaims.add(requestedClaims)
                val reqCount = requestedClaims.size.toLong()
                return zkSystemSpecs.firstOrNull { spec ->
                    spec.system == name && spec.getParam<Long>("num_attributes") == reqCount
                }
            }
        }
        val zkRepo = ZkSystemRepository().add(mockZkSystem)
        val sourceWithConsent = createSource(harness, zkSystemRepository = zkRepo)

        // Case 1: claim_sets = [["d"], ["a", "b", "c"]] with both 1-attr and 3-attr specs.
        // Previously OpenID4VP passed all 4 claims in credentialQuery.claims and failed to find a 4-attr spec!
        val dcqlPreferOneAttr = Json.parseToJsonElement(
            """
                {
                  "credentials": [{
                    "id": "mdl_zk",
                    "format": "mso_mdoc_zk",
                    "meta": {
                      "doctype_value": "${DrivingLicense.MDL_DOCTYPE}",
                      "zk_system_type": [
                        {"system": "mock_zk", "id": "mock_1_attr", "num_attributes": 1},
                        {"system": "mock_zk", "id": "mock_3_attr", "num_attributes": 3}
                      ]
                    },
                    "claims": [
                      {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                      {"id": "b", "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"]},
                      {"id": "c", "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"]},
                      {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]}
                    ],
                    "claim_sets": [["d"], ["a", "b", "c"]]
                  }]
                }
            """.trimIndent()
        ).jsonObject

        val nonce = Random.nextBytes(16).toBase64Url()
        val request1 = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcqlPreferOneAttr,
        )
        val response1 = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = sourceWithConsent.source,
            appId = null,
            origin = ORIGIN,
            request = request1,
            requesterIdentities = emptyList(),
        )
        assertTrue(seenMatchingClaims.isNotEmpty())
        assertTrue(seenMatchingClaims.all { claims ->
            claims.size == 1 && (claims.single() as MdocRequestedClaim).dataElementName == "age_over_18"
        })
        assertEquals(listOf(1), seenProofElementCounts)
        val drBytes1 = response1.vpToken["vp_token"]!!.jsonObject["mdl_zk"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val dr1 = DeviceResponse.fromDataItem(Cbor.decode(drBytes1))
        val zkDoc1 = dr1.zkDocuments.single()
        assertEquals("mock_1_attr", zkDoc1.documentData.zkSystemSpecId)
        assertEquals(setOf("age_over_18"), zkDoc1.documentData.issuerSigned[DrivingLicense.MDL_NAMESPACE]!!.keys)

        // Case 2: claim_sets = [["a", "b"], ["d"]] with only 1-attr spec supported -> DcqlQuery.execute skips ["a", "b"]
        // and selects fallback ["d"] instead of failing later in openID4VPMsoMdoc().
        seenMatchingClaims.clear()
        seenProofElementCounts.clear()
        val dcqlFallbackToOneAttr = Json.parseToJsonElement(
            """
                {
                  "credentials": [{
                    "id": "mdl_zk",
                    "format": "mso_mdoc_zk",
                    "meta": {
                      "doctype_value": "${DrivingLicense.MDL_DOCTYPE}",
                      "zk_system_type": [
                        {"system": "mock_zk", "id": "mock_1_attr", "num_attributes": 1}
                      ]
                    },
                    "claims": [
                      {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                      {"id": "b", "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"]},
                      {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]}
                    ],
                    "claim_sets": [["a", "b"], ["d"]]
                  }]
                }
            """.trimIndent()
        ).jsonObject
        val request2 = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcqlFallbackToOneAttr,
        )
        val response2 = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = sourceWithConsent.source,
            appId = null,
            origin = ORIGIN,
            request = request2,
            requesterIdentities = emptyList(),
        )
        assertEquals(listOf(1), seenProofElementCounts)
        val drBytes2 = response2.vpToken["vp_token"]!!.jsonObject["mdl_zk"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val zkDoc2 = DeviceResponse.fromDataItem(Cbor.decode(drBytes2)).zkDocuments.single()
        assertEquals("mock_1_attr", zkDoc2.documentData.zkSystemSpecId)
        assertEquals(setOf("age_over_18"), zkDoc2.documentData.issuerSigned[DrivingLicense.MDL_NAMESPACE]!!.keys)

        // Case 3: No claim_set matches any ZK spec -> fails with PresentmentCannotSatisfyRequestException before consent
        val consentCountBefore = sourceWithConsent.recordedConsentData.size
        val dcqlNoZkMatch = Json.parseToJsonElement(
            """
                {
                  "credentials": [{
                    "id": "mdl_zk",
                    "format": "mso_mdoc_zk",
                    "meta": {
                      "doctype_value": "${DrivingLicense.MDL_DOCTYPE}",
                      "zk_system_type": [
                        {"system": "mock_zk", "id": "mock_3_attr", "num_attributes": 3}
                      ]
                    },
                    "claims": [
                      {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                      {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]}
                    ],
                    "claim_sets": [["a"], ["d"]]
                  }]
                }
            """.trimIndent()
        ).jsonObject
        val request3 = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcqlNoZkMatch,
        )
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            OpenID4VP.generateResponse(
                version = OpenID4VP.Version.DRAFT_29,
                preselectedDocuments = emptyList(),
                source = sourceWithConsent.source,
                appId = null,
                origin = ORIGIN,
                request = request3,
                requesterIdentities = emptyList(),
            )
        }
        assertEquals(consentCountBefore, sourceWithConsent.recordedConsentData.size)
    }

    @Test
    fun openID4VP_faceMatchingModeAlways_unselectedChv1ClaimSet_doesNotDiscloseChv1() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "age_over_18" to true.toDataItem(),
                    "age_over_21" to false.toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            ),
        )

        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val sourceWithConsent = createSource(
            harness = harness,
            faceMatcher = TestFaceMatcher(),
            faceMatchingMode = FaceMatchingMode.ALWAYS,
        )
        // First claim_set asks for age_over_21 == true + chv_1; credential has age_over_21 == false,
        // so fallback ["age18"] is selected (without chv_1). Under FaceMatchingMode.ALWAYS, face matching
        // still runs for local policy, but chv_1 MUST NOT be disclosed in DeviceNamespaces.
        val dcql = Json.parseToJsonElement(
            """
                {
                  "credentials": [{
                    "id": "mdl_cred",
                    "format": "mso_mdoc",
                    "meta": {"doctype_value": "${DrivingLicense.MDL_DOCTYPE}"},
                    "claims": [
                      {"id": "age21", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_21"], "values": [true]},
                      {"id": "chv1", "path": ["$ISO_23220_5_CHV_1_NAMESPACE", "$ISO_23220_5_CHV_1_DATA_ELEMENT"]},
                      {"id": "age18", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]}
                    ],
                    "claim_sets": [["age21", "chv1"], ["age18"]]
                  }]
                }
            """.trimIndent()
        ).jsonObject

        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
        )

        val response = withContext(promptModel) {
            OpenID4VP.generateResponse(
                version = OpenID4VP.Version.DRAFT_29,
                preselectedDocuments = emptyList(),
                source = sourceWithConsent.source,
                appId = null,
                origin = ORIGIN,
                request = request,
                requesterIdentities = emptyList(),
            )
        }

        assertTrue(facePromptInvoked)
        val encodedDeviceResponse = response.vpToken["vp_token"]!!.jsonObject["mdl_cred"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val handoverInfoDigest = Crypto.digest(
            Algorithm.SHA256,
            Cbor.encode(buildCborArray { add(ORIGIN); add(nonce); add(Simple.NULL) })
        )
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray { add("OpenID4VPDCAPIHandover"); add(handoverInfoDigest) }
        }
        val dr = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        dr.verifySingleDoc(sessionTranscript, transactionData = emptyList())
        val doc = dr.documents.single()
        assertEquals(setOf("age_over_18"), doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.keys)
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
    }

    @Test
    fun iso18013_faceMatching_chv1AlternativeDataElementsRespectsSelectedPermutation() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            ),
        )

        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        setupDialogMock(promptModel) { true }

        val sourceAlways = createSource(
            harness = harness,
            faceMatcher = TestFaceMatcher(),
            faceMatchingMode = FaceMatchingMode.ALWAYS,
        ).source
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val singleDocInfo = DeviceRequestInfo.fromValues(
            useCases = listOf(
                UseCase(
                    mandatory = true,
                    documentSets = listOf(DocumentSet(listOf(0))),
                    purposeHints = emptyMap(),
                )
            )
        )

        // 1. Primary requests portrait with chv_1 as alternative. Credential has portrait, so primary wins
        // and chv_1 is NOT selected -> DeviceNamespaces must NOT disclose chv_1 even under FaceMatchingMode.ALWAYS.
        val reqPortraitPreferred = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1",
            deviceRequestInfo = singleDocInfo,
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false, "portrait" to false),
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(DrivingLicense.MDL_NAMESPACE, "portrait"),
                            alternativeElementSets = listOf(
                                listOf(ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT))
                            ),
                        )
                    )
                ),
            )
        }
        val (drPortrait, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = reqPortraitPreferred,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = sourceAlways,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {},
            )
        }
        drPortrait.verify(sessionTranscript)
        assertNull(
            drPortrait.documents.single().deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]
                ?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        )

        // 2. Primary requests an element absent on the credential, with chv_1 as alternative -> chv_1 IS selected
        // and MUST be disclosed in DeviceNamespaces.
        val reqChv1AlternativeSelected = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1",
            deviceRequestInfo = singleDocInfo,
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false, "missing_element" to false),
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(DrivingLicense.MDL_NAMESPACE, "missing_element"),
                            alternativeElementSets = listOf(
                                listOf(ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT))
                            ),
                        )
                    )
                ),
            )
        }
        val (drChv1, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = reqChv1AlternativeSelected,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = sourceAlways,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {},
            )
        }
        drChv1.verify(sessionTranscript)
        assertEquals(
            Simple.TRUE,
            drChv1.documents.single().deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]
                ?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        )
    }

    @Test
    fun iso18013_buildDeviceRequestFromDcql_sdJwtClaimSets_disclosesSelectedClaimSet() = runTest {
        val dcqlString = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": {"vct_values": ["$PID_VCT"]},
                "claims": [
                  {"id": "a", "path": ["given_name"]},
                  {"id": "b", "path": ["family_name"]},
                  {"id": "c", "path": ["birthdate"]},
                  {"id": "d", "path": ["age_over_18"]}
                ],
                "claim_sets": [["d"], ["a", "b", "c"]]
              }]
            }
        """.trimIndent()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = sessionTranscript,
            dcqlString = dcqlString,
        )

        // 1. Credential has age_over_18 -> selects ["d"] and discloses only age_over_18
        val harnessPreferred = DocumentStoreTestHarness()
        harnessPreferred.initialize()
        provisionPidSdJwtVc(harnessPreferred, includeAgeOver18 = true)
        val sourcePreferred = createSource(harnessPreferred).source
        val (drPreferred, _) = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = sourcePreferred,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = null,
            onDocumentsInFocus = {},
        )
        drPreferred.verify(sessionTranscript)
        val preferredCompact = drPreferred.otherDocuments.single().data.toByteArray().zlibInflate().decodeToString()
        assertEquals(
            listOf("age_over_18"),
            decodeRawDisclosures(preferredCompact).map { it[1].jsonPrimitive.content }
        )

        // 2. Credential lacks age_over_18 -> alternativeDataElements selects fallback ["a", "b", "c"]
        val harnessFallback = DocumentStoreTestHarness()
        harnessFallback.initialize()
        provisionPidSdJwtVc(harnessFallback, includeAgeOver18 = false)
        val sourceFallback = createSource(harnessFallback).source
        val (drFallback, _) = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = sourceFallback,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = null,
            onDocumentsInFocus = {},
        )
        drFallback.verify(sessionTranscript)
        val fallbackCompact = drFallback.otherDocuments.single().data.toByteArray().zlibInflate().decodeToString()
        assertEquals(
            setOf("given_name", "family_name", "birthdate"),
            decodeRawDisclosures(fallbackCompact).map { it[1].jsonPrimitive.content }.toSet()
        )
    }
}
