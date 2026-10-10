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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.ISO_23220_5_CHV_1_DATA_ELEMENT
import org.multipaz.documenttype.ISO_23220_5_CHV_1_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherRepository
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.openid.dcql.DcqlCredentialQueryException
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.FaceNotMatchedException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenID4VPFaceMatchingTest {

    private class TestFaceMatcherSession(referencePortrait: ByteString) : FaceMatcherSession(referencePortrait) {
        override suspend fun feedFrame(frame: CameraFrame) {}
    }

    private class TestFaceMatcher : FaceMatcher {
        override val name = "test"
        override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
            return TestFaceMatcherSession(referencePortrait)
        }
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

    private suspend fun provisionMdl(
        harness: DocumentStoreTestHarness,
        authorizeChv1: Boolean = true,
        authorizeViaNamespace: Boolean = false,
    ) {
        val (keyAuthNamespaces, keyAuthDataElements) = when {
            !authorizeChv1 -> Pair(emptyList<String>(), emptyMap<String, List<String>>())
            authorizeViaNamespace -> Pair(listOf(ISO_23220_5_CHV_1_NAMESPACE), emptyMap())
            else -> Pair(emptyList(), mapOf(ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)))
        }
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedNamespaces = keyAuthNamespaces,
            keyAuthorizedDataElements = keyAuthDataElements
        )
    }

    private fun chv1DirectDcql(): JsonObject {
        return Json.parseToJsonElement(
            """
                {
                  "credentials": [
                    {
                      "id": "mdl_cred",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                        {"path": ["$ISO_23220_5_CHV_1_NAMESPACE", "$ISO_23220_5_CHV_1_DATA_ELEMENT"]}
                      ]
                    }
                  ]
                }
            """.trimIndent()
        ).jsonObject
    }

    private fun claimSetsFallbackDcql(): JsonObject {
        return Json.parseToJsonElement(
            """
                {
                  "credentials": [
                    {
                      "id": "mdl_cred",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "c_given_name", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                        {"id": "c_chv1", "path": ["$ISO_23220_5_CHV_1_NAMESPACE", "$ISO_23220_5_CHV_1_DATA_ELEMENT"]},
                        {"id": "c_portrait", "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"]}
                      ],
                      "claim_sets": [
                        ["c_chv1", "c_given_name"],
                        ["c_portrait", "c_given_name"]
                      ]
                    }
                  ]
                }
            """.trimIndent()
        ).jsonObject
    }

    private fun createPresentmentSource(
        harness: DocumentStoreTestHarness,
        matcher: FaceMatcher? = TestFaceMatcher(),
        faceMatchingMode: FaceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED
    ): SimplePresentmentSource {
        return SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { matcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> faceMatchingMode }
        )
    }

    private suspend fun computeSessionTranscript(origin: String, nonce: String): DataItem {
        val handoverInfo = Cbor.encode(
            buildCborArray {
                add(origin)
                add(nonce)
                add(Simple.NULL)
            }
        )
        val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
        return buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray {
                add("OpenID4VPDCAPIHandover")
                add(handoverInfoDigest)
            }
        }
    }

    @Test
    fun dcqlQueryDirectChv1_Authorized_OnlyIfRequested() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED)

        val query = DcqlQuery.fromJson(chv1DirectDcql())
        val result = query.execute(source)

        assertEquals(1, result.credentialSets.size)
        val option = result.credentialSets[0].options[0]
        val member = option.members[0]
        assertEquals(1, member.matches.size)
        val match = member.matches[0]
        assertTrue(match.faceMatchNeeded)
    }

    @Test
    fun dcqlQueryDirectChv1_AuthorizedByNamespace() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true, authorizeViaNamespace = true)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED)

        val query = DcqlQuery.fromJson(chv1DirectDcql())
        val result = query.execute(source)

        assertEquals(1, result.credentialSets.size)
        val option = result.credentialSets[0].options[0]
        val member = option.members[0]
        assertEquals(1, member.matches.size)
        val match = member.matches[0]
        assertTrue(match.faceMatchNeeded)
    }

    @Test
    fun dcqlQueryDirectChv1_FaceMatchingModeNever() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.NEVER)

        val query = DcqlQuery.fromJson(chv1DirectDcql())
        assertFailsWith<DcqlCredentialQueryException> {
            query.execute(source)
        }
    }

    @Test
    fun dcqlQueryDirectChv1_NotAuthorized() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = false)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED)

        val query = DcqlQuery.fromJson(chv1DirectDcql())
        assertFailsWith<DcqlCredentialQueryException> {
            query.execute(source)
        }
    }

    @Test
    fun dcqlQueryDirectChv1_NoFaceMatcher() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)
        val source = createPresentmentSource(harness, matcher = null)

        val query = DcqlQuery.fromJson(chv1DirectDcql())
        assertFailsWith<DcqlCredentialQueryException> {
            query.execute(source)
        }
    }

    @Test
    fun dcqlQueryClaimSets_Chv1Preferred_Authorized() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED)

        val query = DcqlQuery.fromJson(claimSetsFallbackDcql())
        val result = query.execute(source)

        val match = result.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(match.faceMatchNeeded)
        assertFalse(match.claims.values.any { it is MdocClaim && it.dataElementName == ISO_23220_5_CHV_1_DATA_ELEMENT })
        assertFalse(match.claims.values.any { it is MdocClaim && it.dataElementName == "portrait" })
        assertTrue(match.claims.values.any { it is MdocClaim && it.dataElementName == "given_name" })
    }

    @Test
    fun dcqlQueryClaimSets_Chv1Preferred_FaceMatchingModeNever_FallbackToPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.NEVER)

        val query = DcqlQuery.fromJson(claimSetsFallbackDcql())
        val result = query.execute(source)

        val match = result.credentialSets[0].options[0].members[0].matches[0]
        assertFalse(match.faceMatchNeeded)
        assertTrue(match.claims.values.any { it is MdocClaim && it.dataElementName == "portrait" })
    }

    @Test
    fun dcqlQueryClaimSets_Chv1Preferred_NotAuthorized_FallbackToPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = false)
        val source = createPresentmentSource(harness, faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED)

        val query = DcqlQuery.fromJson(claimSetsFallbackDcql())
        val result = query.execute(source)

        val match = result.credentialSets[0].options[0].members[0].matches[0]
        assertFalse(match.faceMatchNeeded)
        assertTrue(match.claims.values.any { it is MdocClaim && it.dataElementName == "portrait" })
    }

    @Test
    fun openID4VPGenerateResponse_Chv1Requested_PromptSucceeds() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { request ->
            facePromptInvoked = true
            assertEquals(ByteString(byteArrayOf(1, 2, 3, 4)), request.faceMatcherSession.referencePortrait)
            true
        }

        val source = createPresentmentSource(
            harness = harness,
            matcher = testMatcher,
            faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED
        )

        val dcql = chv1DirectDcql()
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
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
                source = source,
                appId = null,
                origin = origin,
                request = request,
                requesterIdentities = emptyList(),
            )
        }

        assertTrue(facePromptInvoked)
        val encodedDeviceResponse = response.vpToken["vp_token"]!!.jsonObject["mdl_cred"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        val sessionTranscript = computeSessionTranscript(origin, nonce)
        deviceResponse.verify(sessionTranscript)

        assertEquals(1, deviceResponse.documents.size)
        val doc = deviceResponse.documents[0]
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertNotNull(chv1)
        assertEquals(Simple.TRUE, chv1)
    }

    @Test
    fun openID4VPGenerateResponse_AuthorizedByNamespace_PromptApproved_SignedSuccessfully() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true, authorizeViaNamespace = true)

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { request ->
            facePromptInvoked = true
            assertEquals(ByteString(byteArrayOf(1, 2, 3, 4)), request.faceMatcherSession.referencePortrait)
            true
        }

        val source = createPresentmentSource(
            harness = harness,
            matcher = testMatcher,
            faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED
        )

        val dcql = chv1DirectDcql()
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
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
                source = source,
                appId = null,
                origin = origin,
                request = request,
                requesterIdentities = emptyList(),
            )
        }

        assertTrue(facePromptInvoked)
        val encodedDeviceResponse = response.vpToken["vp_token"]!!.jsonObject["mdl_cred"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        val sessionTranscript = computeSessionTranscript(origin, nonce)
        deviceResponse.verify(sessionTranscript)

        assertEquals(1, deviceResponse.documents.size)
        val doc = deviceResponse.documents[0]
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertNotNull(chv1)
        assertEquals(Simple.TRUE, chv1)
    }

    @Test
    fun openID4VPGenerateResponse_Chv1Requested_PromptFails() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { _ ->
            facePromptInvoked = true
            false // Match failed
        }

        val source = createPresentmentSource(
            harness = harness,
            matcher = testMatcher,
            faceMatchingMode = FaceMatchingMode.ONLY_IF_REQUESTED
        )

        val dcql = chv1DirectDcql()
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
        )

        assertFailsWith<FaceNotMatchedException> {
            withContext(promptModel) {
                OpenID4VP.generateResponse(
                    version = OpenID4VP.Version.DRAFT_29,
                    preselectedDocuments = emptyList(),
                    source = source,
                    appId = null,
                    origin = origin,
                    request = request,
                    requesterIdentities = emptyList(),
                )
            }
        }
        assertTrue(facePromptInvoked)
    }

    @Test
    fun openID4VPGenerateResponse_ClaimSetsFallbackToPortrait_NoPrompt() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        provisionMdl(harness, authorizeChv1 = true)

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { _ ->
            facePromptInvoked = true
            true
        }

        // FaceMatchingMode is NEVER, so claim set falls back to portrait
        val source = createPresentmentSource(
            harness = harness,
            matcher = testMatcher,
            faceMatchingMode = FaceMatchingMode.NEVER
        )

        val dcql = claimSetsFallbackDcql()
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
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
                source = source,
                appId = null,
                origin = origin,
                request = request,
                requesterIdentities = emptyList(),
            )
        }

        assertFalse(facePromptInvoked)
        val encodedDeviceResponse = response.vpToken["vp_token"]!!.jsonObject["mdl_cred"]!!
            .jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        val sessionTranscript = computeSessionTranscript(origin, nonce)
        deviceResponse.verify(sessionTranscript)

        assertEquals(1, deviceResponse.documents.size)
        val doc = deviceResponse.documents[0]
        // Portrait should be present in issuer namespaces
        assertNotNull(doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]?.get("portrait"))
        // CHV_1 should not be present in device namespaces
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertNull(chv1)
    }

    @Test
    fun testGetFaceMatchingModeCalledAtMostOnce() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val testMatcher = TestFaceMatcher()
        provisionMdl(
            harness = harness,
            authorizeChv1 = true,
            authorizeViaNamespace = true
        )

        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        setupDialogMock(promptModel) { true }

        var callCount = 0
        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { credential, requesterIdentities ->
                callCount++
                FaceMatchingMode.ONLY_IF_REQUESTED
            }
        )

        val dcql = chv1DirectDcql()
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
        )

        withContext(promptModel) {
            OpenID4VP.generateResponse(
                version = OpenID4VP.Version.DRAFT_29,
                preselectedDocuments = emptyList(),
                source = source,
                appId = null,
                origin = origin,
                request = request,
                requesterIdentities = emptyList(),
            )
        }

        assertEquals(1, callCount)
    }
}
