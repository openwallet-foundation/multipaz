package org.multipaz.presentment

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
import org.multipaz.cbor.Simple
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.ISO_23220_5_CHV_1_DATA_ELEMENT
import org.multipaz.documenttype.ISO_23220_5_CHV_1_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherRepository
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.mdoc.request.AlternativeDataElementSet
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.ElementReference
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.MdocRequestedClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MdocFaceMatchingTest {

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

    @Test
    fun testFaceMatchingOnlyIfRequested_Requested_Authorized() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        // Provision mDL with portrait and authorized CHV_1 device key element
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { request ->
            facePromptInvoked = true
            assertEquals(ByteString(byteArrayOf(1, 2, 3, 4)), request.faceMatcherSession.referencePortrait)
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(facePromptInvoked)
        dr.verify(sessionTranscript)
        assertEquals(1, dr.documents.size)
        val doc = dr.documents[0]
        assertEquals("Erika", doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["given_name"]!!.dataElementValue.asTstr)
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertNotNull(chv1)
        assertEquals(Simple.TRUE, chv1)
    }

    @Test
    fun testFaceMatchingOnlyIfRequested_Requested_AuthorizedByNamespace() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        // Provision mDL with portrait and authorized CHV_1 device key namespace (without data elements)
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedNamespaces = listOf(ISO_23220_5_CHV_1_NAMESPACE),
            keyAuthorizedDataElements = emptyMap()
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) { request ->
            facePromptInvoked = true
            assertEquals(ByteString(byteArrayOf(1, 2, 3, 4)), request.faceMatcherSession.referencePortrait)
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(facePromptInvoked)
        dr.verify(sessionTranscript)
        assertEquals(1, dr.documents.size)
        val doc = dr.documents[0]
        assertEquals("Erika", doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["given_name"]!!.dataElementValue.asTstr)
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertNotNull(chv1)
        assertEquals(Simple.TRUE, chv1)
    }

    @Test
    fun testFaceMatchingOnlyIfRequested_Requested_NotAuthorized() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        // Provision mDL without CHV_1 authorized
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = emptyMap()
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(!facePromptInvoked)
        dr.verify(sessionTranscript)
        assertEquals(1, dr.documents.size)
        val doc = dr.documents[0]
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
    }

    @Test
    fun testFaceMatchingNever() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.NEVER }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(!facePromptInvoked)
        dr.verify(sessionTranscript)
        assertNull(dr.documents[0].deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
    }

    @Test
    fun testFaceMatchingAlways_Requested() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ALWAYS }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(facePromptInvoked)
        dr.verify(sessionTranscript)
        val chv1 = dr.documents[0].deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertEquals(Simple.TRUE, chv1)
    }

    @Test
    fun testFaceMatchingFailed_ThrowsFaceNotMatchedException() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        setupDialogMock(promptModel) {
            false // Match failed
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        assertFailsWith<FaceNotMatchedException> {
            withContext(promptModel) {
                mdocPresentment(
                    deviceRequest = deviceRequest,
                    eReaderKey = null,
                    sessionTranscript = sessionTranscript,
                    source = source,
                    keyAgreementPossible = emptyList(),
                    requesterAppId = null,
                    requesterOrigin = null,
                    onDocumentsInFocus = {}
                )
            }
        }
    }

    @Test
    fun testChv1Requested_FaceMatchingNever_FailsExecute() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.NEVER }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        assertFailsWith<Iso18015ResponseException> {
            deviceRequest.execute(source)
        }
    }

    @Test
    fun testAlternativeDataElements_Chv1Preferred_ModeOnlyIfRequested_PicksChv1() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val docRequestInfo = DocRequestInfo(
            alternativeDataElements = listOf(
                AlternativeDataElementSet(
                    requestedElement = ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT),
                    alternativeElementSets = listOf(
                        listOf(ElementReference(DrivingLicense.MDL_NAMESPACE, "portrait"))
                    )
                )
            )
        )
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                ),
                docRequestInfo = docRequestInfo
            )
        }

        val queryResult = deviceRequest.execute(source)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(match.faceMatchNeeded)
        val chv1ClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == ISO_23220_5_CHV_1_NAMESPACE && it.dataElementName == ISO_23220_5_CHV_1_DATA_ELEMENT
        }
        assertNull(chv1ClaimKey)
        val portraitClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == DrivingLicense.MDL_NAMESPACE && it.dataElementName == "portrait"
        }
        assertNull(portraitClaimKey)

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(facePromptInvoked)
        dr.verify(sessionTranscript)
        val doc = dr.documents[0]
        val chv1 = doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT)
        assertEquals(Simple.TRUE, chv1)
        assertNull(doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]?.get("portrait"))
    }

    @Test
    fun testAlternativeDataElements_Chv1Preferred_ModeNever_FallsBackToPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.NEVER }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val docRequestInfo = DocRequestInfo(
            alternativeDataElements = listOf(
                AlternativeDataElementSet(
                    requestedElement = ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT),
                    alternativeElementSets = listOf(
                        listOf(ElementReference(DrivingLicense.MDL_NAMESPACE, "portrait"))
                    )
                )
            )
        )
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                ),
                docRequestInfo = docRequestInfo
            )
        }

        val queryResult = deviceRequest.execute(source)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(!match.faceMatchNeeded)
        val chv1ClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == ISO_23220_5_CHV_1_NAMESPACE && it.dataElementName == ISO_23220_5_CHV_1_DATA_ELEMENT
        }
        assertNull(chv1ClaimKey)
        val portraitClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == DrivingLicense.MDL_NAMESPACE && it.dataElementName == "portrait"
        }
        assertNotNull(portraitClaimKey)

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(!facePromptInvoked)
        dr.verify(sessionTranscript)
        val doc = dr.documents[0]
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
        assertNotNull(doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]?.get("portrait"))
    }

    @Test
    fun testAlternativeDataElements_Chv1Preferred_NotAuthorizedInMso_FallsBackToPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val docRequestInfo = DocRequestInfo(
            alternativeDataElements = listOf(
                AlternativeDataElementSet(
                    requestedElement = ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT),
                    alternativeElementSets = listOf(
                        listOf(ElementReference(DrivingLicense.MDL_NAMESPACE, "portrait"))
                    )
                )
            )
        )
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                ),
                docRequestInfo = docRequestInfo
            )
        }

        val queryResult = deviceRequest.execute(source)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(!match.faceMatchNeeded)
        val portraitClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == DrivingLicense.MDL_NAMESPACE && it.dataElementName == "portrait"
        }
        assertNotNull(portraitClaimKey)

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(!facePromptInvoked)
        dr.verify(sessionTranscript)
        val doc = dr.documents[0]
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
        assertNotNull(doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]?.get("portrait"))
    }

    @Test
    fun testAlternativeDataElements_PortraitPreferred_Chv1Alternative_ModeOnlyIfRequested_PicksPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val docRequestInfo = DocRequestInfo(
            alternativeDataElements = listOf(
                AlternativeDataElementSet(
                    requestedElement = ElementReference(DrivingLicense.MDL_NAMESPACE, "portrait"),
                    alternativeElementSets = listOf(
                        listOf(ElementReference(ISO_23220_5_CHV_1_NAMESPACE, ISO_23220_5_CHV_1_DATA_ELEMENT))
                    )
                )
            )
        )
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false, "portrait" to false),
                ),
                docRequestInfo = docRequestInfo
            )
        }

        val queryResult = deviceRequest.execute(source)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(!match.faceMatchNeeded)
        val portraitClaimKey = match.claims.keys.find {
            it is MdocRequestedClaim && it.namespaceName == DrivingLicense.MDL_NAMESPACE && it.dataElementName == "portrait"
        }
        assertNotNull(portraitClaimKey)

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(!facePromptInvoked)
        dr.verify(sessionTranscript)
        val doc = dr.documents[0]
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
        assertNotNull(doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]?.get("portrait"))
    }

    @Test
    fun testModeAlways_NoChv1Requested_PerformsFaceMatching() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
        val matcherRepo = FaceMatcherRepository().add(testMatcher)
        val promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()

        var facePromptInvoked = false
        setupDialogMock(promptModel) {
            facePromptInvoked = true
            true
        }

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            getFaceMatcherFn = { testMatcher },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ALWAYS }
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                )
            )
        }

        val queryResult = deviceRequest.execute(source)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(match.faceMatchNeeded)

        val (dr, _) = withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertTrue(facePromptInvoked)
        dr.verify(sessionTranscript)
        val doc = dr.documents[0]
        assertNull(doc.deviceNamespaces.data[ISO_23220_5_CHV_1_NAMESPACE]?.get(ISO_23220_5_CHV_1_DATA_ELEMENT))
    }

    @Test
    fun testGetFaceMatchingModeCalledAtMostOnce() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            )
        )

        val testMatcher = TestFaceMatcher()
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

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_23220_5_CHV_1_NAMESPACE to mapOf(ISO_23220_5_CHV_1_DATA_ELEMENT to false),
                )
            )
        }

        withContext(promptModel) {
            mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }

        assertEquals(1, callCount)
    }
}
