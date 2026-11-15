package org.multipaz.mdoc.request

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.claim.MdocClaim
import org.multipaz.consent.PresentmentPurpose
import org.multipaz.consent.SimpleConsentClaim
import org.multipaz.consent.classifyPurposes
import org.multipaz.consent.defaultClassifySimpleConsent
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.IDPass
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.util.asBooleanOrNull
import org.multipaz.mdoc.util.findNearestAgeOver
import org.multipaz.mdoc.util.isMdlAgeOverElement
import org.multipaz.mdoc.util.isValidMdlAgeOverSubstitution
import org.multipaz.mdoc.util.parseAgeOverThreshold
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.mdocPresentmentGenerateResponse
import org.multipaz.presentment.mdocPresentmentObtainConsent
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.verification.MdocVerifiedPresentation
import org.multipaz.verification.VerificationUtil

/**
 * Tests for ISO/IEC 18013-5 Second Edition DIS Clause 13.4.6 and Annex D.2.2 (`age_over_NN`).
 */
class MdlAgeOverTest {

    companion object {
        private val DUMMY_TRANSCRIPT: DataItem = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add("test-handover")
        }

        private val SINGLE_DOC_USE_CASE = DeviceRequestInfo.fromValues(
            useCases = listOf(
                UseCase(
                    mandatory = true,
                    documentSets = listOf(DocumentSet(listOf(0))),
                    purposeHints = emptyMap()
                )
            )
        )

        private fun buildMdlDeviceRequest(
            version: String,
            elements: Map<String, Boolean>,
            docRequestInfo: DocRequestInfo? = null,
        ): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = DUMMY_TRANSCRIPT,
                deviceRequestInfo = if (version == "1.1") SINGLE_DOC_USE_CASE else null,
                version = version,
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to elements),
                    docRequestInfo = if (version == "1.1") docRequestInfo else null,
                )
            }
        }
    }

    @Test
    fun parseAgeOverThreshold_strictTwoAsciiDigits() {
        assertEquals(0, parseAgeOverThreshold("age_over_00"))
        assertEquals(8, parseAgeOverThreshold("age_over_08"))
        assertEquals(18, parseAgeOverThreshold("age_over_18"))
        assertEquals(99, parseAgeOverThreshold("age_over_99"))

        assertNull(parseAgeOverThreshold("age_over_"))
        assertNull(parseAgeOverThreshold("age_over_8"))
        assertNull(parseAgeOverThreshold("age_over_100"))
        assertNull(parseAgeOverThreshold("age_over_ab"))
        assertNull(parseAgeOverThreshold("age_over_-1"))
        assertNull(parseAgeOverThreshold("age_over_1a"))
        assertNull(parseAgeOverThreshold("age_over_18 "))
        assertNull(parseAgeOverThreshold("age_in_years"))

        assertTrue(isMdlAgeOverElement(DrivingLicense.MDL_DOCTYPE, DrivingLicense.MDL_NAMESPACE, "age_over_21"))
        assertEquals(
            false,
            isMdlAgeOverElement(PhotoID.PHOTO_ID_DOCTYPE, PhotoID.ISO_23220_2_NAMESPACE, "age_over_21")
        )
        assertEquals(
            false,
            isMdlAgeOverElement(IDPass.IDPASS_DOCTYPE, DrivingLicense.MDL_NAMESPACE, "age_over_21")
        )
        assertEquals(
            false,
            isMdlAgeOverElement(DrivingLicense.MDL_DOCTYPE, DrivingLicense.AAMVA_NAMESPACE, "age_over_21")
        )
    }

    @Test
    fun annexD22_tableD1_holderAge19With18TrueAnd21False() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "holder-age-19",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "age_over_18" to Simple.TRUE,
                    "age_over_21" to Simple.FALSE,
                )
            )
        )

        // ISO/IEC 18013-5 DIS Annex D.2.2 Table D.1:
        // Holder is 19 years old; mDL contains age_over_18 = true, age_over_21 = false.
        val expectedByRequestedThreshold = listOf(
            "age_over_00" to ("age_over_18" to true),
            "age_over_15" to ("age_over_18" to true),
            "age_over_18" to ("age_over_18" to true),
            "age_over_19" to null,
            "age_over_20" to null,
            "age_over_21" to ("age_over_21" to false),
            "age_over_25" to ("age_over_21" to false),
            "age_over_99" to ("age_over_21" to false),
        )

        val now = Clock.System.now().truncateToWholeSeconds()
        for (version in listOf("1.0", "1.1")) {
            for ((requestedElement, expectedReturned) in expectedByRequestedThreshold) {
                val request = buildMdlDeviceRequest(
                    version = version,
                    elements = mapOf(
                        "given_name" to false,
                        requestedElement to false,
                    )
                )

                if (expectedReturned == null) {
                    if (version == "1.1") {
                        assertFailsWith<PresentmentCannotSatisfyRequestException>(
                            message = "Expected v1.1 $requestedElement to fail when neither Step 1 nor Step 2 matches"
                        ) {
                            mdocPresentmentObtainConsent(
                                deviceRequest = request,
                                source = harness.presentmentSource,
                            )
                        }
                    } else {
                        // In v1.0, unresolvable elements are omitted from the response per Clause 8.3.2.1.2.2 / 13.4.6 Step 3.
                        val selection = mdocPresentmentObtainConsent(
                            deviceRequest = request,
                            source = harness.presentmentSource,
                        )
                        val match = selection.matches.single()
                        assertEquals(listOf("given_name"), match.claims.values.map { (it as MdocClaim).dataElementName })
                        val response = mdocPresentmentGenerateResponse(
                            selection = selection,
                            deviceRequest = request,
                            eReaderKey = null,
                            sessionTranscript = DUMMY_TRANSCRIPT,
                            source = harness.presentmentSource,
                            creationTime = now,
                        )
                        response.deviceResponse.verify(sessionTranscript = DUMMY_TRANSCRIPT, atTime = now)
                        val returnedDoc = response.deviceResponse.documents.single()
                        val nsItems = returnedDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!
                        assertEquals(setOf("given_name"), nsItems.keys)

                        // Verifier requiring requestedElement rejects response that omitted it
                        assertFailsWith<IllegalStateException> {
                            VerificationUtil.verifyMdocDeviceResponse(
                                now = now,
                                deviceResponse = response.deviceResponse.toDataItem(),
                                sessionTranscript = DUMMY_TRANSCRIPT,
                                eReaderKey = null,
                                documentTypeRepository = harness.documentTypeRepository,
                                zkSystemRepository = null,
                                request = request,
                            )
                        }
                    }
                } else {
                    val (expectedKey, expectedBool) = expectedReturned
                    val selection = mdocPresentmentObtainConsent(
                        deviceRequest = request,
                        source = harness.presentmentSource,
                    )
                    val matchedClaims = selection.matches.single().claims.values.map { it as MdocClaim }
                    val ageClaim = matchedClaims.single { it.dataElementName.startsWith("age_over_") }
                    assertEquals(expectedKey, ageClaim.dataElementName)
                    assertEquals(if (expectedBool) Simple.TRUE else Simple.FALSE, ageClaim.value)

                    val response = mdocPresentmentGenerateResponse(
                        selection = selection,
                        deviceRequest = request,
                        eReaderKey = null,
                        sessionTranscript = DUMMY_TRANSCRIPT,
                        source = harness.presentmentSource,
                        creationTime = now,
                    )
                    response.deviceResponse.verify(sessionTranscript = DUMMY_TRANSCRIPT, atTime = now)
                    val returnedDoc = response.deviceResponse.documents.single()
                    val nsItems = returnedDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!
                    assertEquals(setOf("given_name", expectedKey), nsItems.keys)
                    assertEquals(expectedBool, nsItems[expectedKey]!!.dataElementValue.asBoolean)

                    val verified = VerificationUtil.verifyMdocDeviceResponse(
                        now = now,
                        deviceResponse = response.deviceResponse.toDataItem(),
                        sessionTranscript = DUMMY_TRANSCRIPT,
                        eReaderKey = null,
                        documentTypeRepository = harness.documentTypeRepository,
                        zkSystemRepository = null,
                        request = request,
                    )
                    val vp = verified.single() as MdocVerifiedPresentation
                    val verifiedAgeClaim = vp.issuerSignedClaims.single { it.dataElementName.startsWith("age_over_") }
                    assertEquals(expectedKey, verifiedAgeClaim.dataElementName)
                    assertEquals(expectedBool, verifiedAgeClaim.value.asBoolean)
                }
            }
        }
    }

    @Test
    fun nearestSelectionAmongMultipleTrueAndFalseAttestations_andBoundaries() {
        val entries = listOf(
            "age_over_00" to Simple.TRUE,
            "age_over_13" to Simple.TRUE,
            "age_over_16" to Simple.TRUE,
            "age_over_18" to Simple.TRUE,
            "age_over_21" to Simple.TRUE,
            "age_over_25" to Simple.TRUE,
            "age_over_60" to Simple.FALSE,
            "age_over_65" to Simple.FALSE,
            "age_over_68" to Simple.FALSE,
            "age_over_99" to Simple.FALSE,
        )

        fun lookup(req: String): Pair<String, Boolean>? {
            return findNearestAgeOver(
                requestedDataElementName = req,
                candidates = entries,
                getName = { it.first },
                getBooleanValue = { it.second.asBooleanOrNull() }
            )?.let { it.first to (it.second == Simple.TRUE) }
        }

        // Exact matches
        assertEquals("age_over_00" to true, lookup("age_over_00"))
        assertEquals("age_over_18" to true, lookup("age_over_18"))
        assertEquals("age_over_60" to false, lookup("age_over_60"))
        assertEquals("age_over_99" to false, lookup("age_over_99"))

        // Step 1: Nearest true >= NN (smallest nn - NN)
        assertEquals("age_over_13" to true, lookup("age_over_05"))
        assertEquals("age_over_16" to true, lookup("age_over_15"))
        assertEquals("age_over_21" to true, lookup("age_over_19"))
        assertEquals("age_over_25" to true, lookup("age_over_22"))

        // Step 3: Between highest true (25) and lowest false (60) -> null
        assertNull(lookup("age_over_26"))
        assertNull(lookup("age_over_40"))
        assertNull(lookup("age_over_59"))

        // Step 2: Nearest false <= NN (smallest NN - nn)
        assertEquals("age_over_60" to false, lookup("age_over_61"))
        assertEquals("age_over_60" to false, lookup("age_over_64"))
        assertEquals("age_over_65" to false, lookup("age_over_66"))
        assertEquals("age_over_68" to false, lookup("age_over_75"))
        assertEquals("age_over_68" to false, lookup("age_over_98"))
    }

    @Test
    fun alternativeDataElements_step1AndStep2AvoidFallback_step3UsesFallback() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mdl-holder-30",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "age_over_21" to Simple.TRUE,
                    "age_over_25" to Simple.TRUE,
                    "age_over_60" to Simple.FALSE,
                    "age_in_years" to 30.toDataItem(),
                    "birth_date" to LocalDate.parse("1995-05-01").toDataItemFullDate(),
                )
            )
        )

        fun buildAgeQueryWithAlternatives(requestedAgeElement: String): DeviceRequest {
            return buildMdlDeviceRequest(
                version = "1.1",
                elements = mapOf(
                    "given_name" to false,
                    requestedAgeElement to false,
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                namespace = DrivingLicense.MDL_NAMESPACE,
                                dataElement = requestedAgeElement,
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        namespace = DrivingLicense.MDL_NAMESPACE,
                                        dataElement = "age_in_years",
                                    )
                                ),
                                listOf(
                                    ElementReference(
                                        namespace = DrivingLicense.MDL_NAMESPACE,
                                        dataElement = "birth_date",
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }

        // 1. Step 1: Request age_over_18 -> satisfied by age_over_21 = true without disclosing age_in_years / birth_date
        val step1Selection = mdocPresentmentObtainConsent(
            deviceRequest = buildAgeQueryWithAlternatives("age_over_18"),
            source = harness.presentmentSource,
        )
        assertEquals(
            listOf("given_name", "age_over_21"),
            step1Selection.matches.single().claims.values.map { (it as MdocClaim).dataElementName }
        )

        // 2. Step 2: Request age_over_65 -> satisfied by age_over_60 = false without disclosing age_in_years / birth_date
        val step2Selection = mdocPresentmentObtainConsent(
            deviceRequest = buildAgeQueryWithAlternatives("age_over_65"),
            source = harness.presentmentSource,
        )
        assertEquals(
            listOf("given_name", "age_over_60"),
            step2Selection.matches.single().claims.values.map { (it as MdocClaim).dataElementName }
        )

        // 3. Step 3: Request age_over_50 (gap between 25=T and 60=F) -> falls back to age_in_years
        val step3Request = buildAgeQueryWithAlternatives("age_over_50")
        val step3Selection = mdocPresentmentObtainConsent(
            deviceRequest = step3Request,
            source = harness.presentmentSource,
        )
        assertEquals(
            listOf("given_name", "age_in_years"),
            step3Selection.matches.single().claims.values.map { (it as MdocClaim).dataElementName }
        )
        val now = Clock.System.now().truncateToWholeSeconds()
        val step3Response = mdocPresentmentGenerateResponse(
            selection = step3Selection,
            deviceRequest = step3Request,
            eReaderKey = null,
            sessionTranscript = DUMMY_TRANSCRIPT,
            source = harness.presentmentSource,
            creationTime = now,
        )
        val verified = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = step3Response.deviceResponse.toDataItem(),
            sessionTranscript = DUMMY_TRANSCRIPT,
            eReaderKey = null,
            documentTypeRepository = harness.documentTypeRepository,
            zkSystemRepository = null,
            request = step3Request,
        )
        assertEquals(
            setOf("given_name", "age_in_years"),
            (verified.single() as MdocVerifiedPresentation).issuerSignedClaims.map { it.dataElementName }.toSet()
        )
    }

    @Test
    fun rangeQueryAndCollapsingDeduplicationWithIntentToRetainMerge() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mdl-holder-30",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "age_over_21" to Simple.TRUE,
                    "age_over_60" to Simple.FALSE,
                )
            )
        )
        val now = Clock.System.now().truncateToWholeSeconds()

        // 1. Range query: age_over_18 + age_over_65 -> returns age_over_21 = true AND age_over_60 = false
        val rangeRequest = buildMdlDeviceRequest(
            version = "1.1",
            elements = mapOf(
                "age_over_18" to false,
                "age_over_65" to false,
            )
        )
        val rangeSelection = mdocPresentmentObtainConsent(
            deviceRequest = rangeRequest,
            source = harness.presentmentSource,
        )
        assertEquals(
            listOf("age_over_21" to true, "age_over_60" to false),
            rangeSelection.matches.single().claims.values.map {
                val mc = it as MdocClaim
                mc.dataElementName to mc.value.asBoolean
            }
        )
        val rangeResponse = mdocPresentmentGenerateResponse(
            selection = rangeSelection,
            deviceRequest = rangeRequest,
            eReaderKey = null,
            sessionTranscript = DUMMY_TRANSCRIPT,
            source = harness.presentmentSource,
            creationTime = now,
        )
        val verifiedRange = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = rangeResponse.deviceResponse.toDataItem(),
            sessionTranscript = DUMMY_TRANSCRIPT,
            eReaderKey = null,
            documentTypeRepository = harness.documentTypeRepository,
            zkSystemRepository = null,
            request = rangeRequest,
        )
        assertEquals(
            setOf("age_over_21", "age_over_60"),
            (verifiedRange.single() as MdocVerifiedPresentation).issuerSignedClaims.map { it.dataElementName }.toSet()
        )

        // 2. Collapsing query: age_over_18 (intentToRetain=false) + age_over_20 (intentToRetain=true)
        // Both resolve to age_over_21 = true -> deduplicated to a single claim with intentToRetain = true
        val collapseRequest = buildMdlDeviceRequest(
            version = "1.1",
            elements = mapOf(
                "age_over_18" to false,
                "age_over_20" to true,
            )
        )
        val collapseSelection = mdocPresentmentObtainConsent(
            deviceRequest = collapseRequest,
            source = harness.presentmentSource,
        )
        val collapseMatch = collapseSelection.matches.single()
        assertEquals(1, collapseMatch.claims.size)
        val (resolvedReqClaim, resolvedClaim) = collapseMatch.claims.entries.single()
        assertEquals("age_over_21", (resolvedReqClaim as MdocRequestedClaim).dataElementName)
        assertEquals(true, resolvedReqClaim.intentToRetain)
        assertEquals("age_over_21", (resolvedClaim as MdocClaim).dataElementName)

        val collapseResponse = mdocPresentmentGenerateResponse(
            selection = collapseSelection,
            deviceRequest = collapseRequest,
            eReaderKey = null,
            sessionTranscript = DUMMY_TRANSCRIPT,
            source = harness.presentmentSource,
            creationTime = now,
        )
        collapseResponse.deviceResponse.verify(sessionTranscript = DUMMY_TRANSCRIPT, atTime = now)
        val mdocDoc = collapseResponse.deviceResponse.documents.single()
        assertEquals(listOf("age_over_21"), mdocDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.keys.toList())
        val verifiedCollapse = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = collapseResponse.deviceResponse.toDataItem(),
            sessionTranscript = DUMMY_TRANSCRIPT,
            eReaderKey = null,
            documentTypeRepository = harness.documentTypeRepository,
            zkSystemRepository = null,
            request = collapseRequest,
        )
        assertEquals(1, (verifiedCollapse.single() as MdocVerifiedPresentation).issuerSignedClaims.size)
    }

    @Test
    fun maxTwoAgeOverElementsEnforcedAcrossSingleAndMultiDocRequestsAndIssuerNamespacesFilter() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mdl-many-ages",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("Erika"),
                    "age_over_16" to Simple.TRUE,
                    "age_over_18" to Simple.TRUE,
                    "age_over_21" to Simple.TRUE,
                    "age_over_65" to Simple.FALSE,
                )
            )
        )
        val now = Clock.System.now().truncateToWholeSeconds()

        // 1. Single DocRequest asking for 4 age_over_NN claims (both v1.0 and v1.1):
        // Builder logs a warning (does not throw), wallet returns at most 2 age_over_NN elements,
        // and verifier accepts the 2-element response.
        for (version in listOf("1.0", "1.1")) {
            val overLimitRequest = buildMdlDeviceRequest(
                version = version,
                elements = linkedMapOf(
                    "given_name" to false,
                    "age_over_16" to false,
                    "age_over_18" to false,
                    "age_over_21" to false,
                    "age_over_65" to false,
                )
            )
            val selection = mdocPresentmentObtainConsent(
                deviceRequest = overLimitRequest,
                source = harness.presentmentSource,
            )
            val matchedNames = selection.matches.single().claims.values.map { (it as MdocClaim).dataElementName }
            assertEquals(listOf("given_name", "age_over_16", "age_over_18"), matchedNames)

            val response = mdocPresentmentGenerateResponse(
                selection = selection,
                deviceRequest = overLimitRequest,
                eReaderKey = null,
                sessionTranscript = DUMMY_TRANSCRIPT,
                source = harness.presentmentSource,
                creationTime = now,
            )
            response.deviceResponse.verify(sessionTranscript = DUMMY_TRANSCRIPT, atTime = now)
            val returnedDoc = response.deviceResponse.documents.single()
            val returnedKeys = returnedDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.keys
            assertEquals(setOf("given_name", "age_over_16", "age_over_18"), returnedKeys)

            val verified = VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = response.deviceResponse.toDataItem(),
                sessionTranscript = DUMMY_TRANSCRIPT,
                eReaderKey = null,
                documentTypeRepository = harness.documentTypeRepository,
                zkSystemRepository = null,
                request = overLimitRequest,
            )
            assertEquals(1, verified.size)
        }

        // 2. Multi-DocRequest DeviceResponse asking for 2 mDL DocRequests each with 2 age_over_NN claims:
        // Total mDL age_over_NN across the entire DeviceResponse is capped at 2.
        val multiDocRequest = buildDeviceRequest(
            sessionTranscript = DUMMY_TRANSCRIPT,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0, 1))),
                        purposeHints = emptyMap()
                    )
                )
            ),
            version = "1.1"
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to linkedMapOf(
                        "given_name" to false,
                        "age_over_16" to false,
                        "age_over_18" to false,
                    )
                )
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to linkedMapOf(
                        "given_name" to false,
                        "age_over_21" to false,
                        "age_over_65" to false,
                    )
                )
            )
        }
        val multiSelection = mdocPresentmentObtainConsent(
            deviceRequest = multiDocRequest,
            source = harness.presentmentSource,
        )
        val multiResponse = mdocPresentmentGenerateResponse(
            selection = multiSelection,
            deviceRequest = multiDocRequest,
            eReaderKey = null,
            sessionTranscript = DUMMY_TRANSCRIPT,
            source = harness.presentmentSource,
            creationTime = now,
        )
        multiResponse.deviceResponse.verify(sessionTranscript = DUMMY_TRANSCRIPT, atTime = now)
        val totalReturnedAgeOverAcrossResponse = multiResponse.deviceResponse.documents.sumOf { doc ->
            doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]
                ?.keys
                ?.count { parseAgeOverThreshold(it) != null } ?: 0
        }
        assertEquals(2, totalReturnedAgeOverAcrossResponse)

        // 3. Direct IssuerNamespaces.filter caps at 2 when iso18013AgeOverSubstitution = true, and does not cap when false
        val rawNs = buildIssuerNamespaces {
            addNamespace(DrivingLicense.MDL_NAMESPACE) {
                addDataElement("age_over_16", Simple.TRUE)
                addDataElement("age_over_18", Simple.TRUE)
                addDataElement("age_over_21", Simple.TRUE)
            }
        }
        val threeClaims = listOf("age_over_16", "age_over_18", "age_over_21").map { de ->
            MdocRequestedClaim(
                docType = DrivingLicense.MDL_DOCTYPE,
                namespaceName = DrivingLicense.MDL_NAMESPACE,
                dataElementName = de,
                intentToRetain = false,
            )
        }
        assertEquals(
            setOf("age_over_16", "age_over_18"),
            rawNs.filter(threeClaims, iso18013AgeOverSubstitution = true).data[DrivingLicense.MDL_NAMESPACE]!!.keys
        )
        assertEquals(
            setOf("age_over_16", "age_over_18", "age_over_21"),
            rawNs.filter(threeClaims, iso18013AgeOverSubstitution = false).data[DrivingLicense.MDL_NAMESPACE]!!.keys
        )
    }

    @Test
    fun docTypeAndProtocolScoping_mdlIso18013Only() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(AgeVerification.getDocumentType())
        harness.documentTypeRepository.addDocumentType(IDPass.getDocumentType())

        // Provision PhotoID, AgeVerification, IDPass, and mDL each with age_over_21 = true (no age_over_18)
        harness.provisionMdoc(
            displayName = "my-photoid",
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            data = mapOf(PhotoID.ISO_23220_2_NAMESPACE to listOf("age_over_21" to Simple.TRUE))
        )
        harness.provisionMdoc(
            displayName = "my-av",
            docType = AgeVerification.AV_DOCTYPE,
            data = mapOf(AgeVerification.AV_NAMESPACE to listOf("age_over_21" to Simple.TRUE))
        )
        // IDPass reuses org.iso.18013.5.1 namespace! Verify docType check prevents substitution on IDPass.
        harness.provisionMdoc(
            displayName = "my-idpass",
            docType = IDPass.IDPASS_DOCTYPE,
            data = mapOf(DrivingLicense.MDL_NAMESPACE to listOf("age_over_21" to Simple.TRUE))
        )
        harness.provisionMdoc(
            displayName = "my-mdl",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "age_over_21" to Simple.TRUE,
                    "age_over_25" to Simple.TRUE,
                    "age_over_60" to Simple.FALSE,
                )
            )
        )

        for ((docType, ns) in listOf(
            PhotoID.PHOTO_ID_DOCTYPE to PhotoID.ISO_23220_2_NAMESPACE,
            AgeVerification.AV_DOCTYPE to AgeVerification.AV_NAMESPACE,
            IDPass.IDPASS_DOCTYPE to DrivingLicense.MDL_NAMESPACE,
        )) {
            val req = buildDeviceRequest(
                sessionTranscript = DUMMY_TRANSCRIPT,
                deviceRequestInfo = SINGLE_DOC_USE_CASE,
                version = "1.1"
            ) {
                addDocRequest(
                    docType = docType,
                    nameSpaces = mapOf(ns to mapOf("age_over_18" to false))
                )
            }
            assertFailsWith<Iso18015ResponseException>(
                message = "Expected non-mDL docType $docType not to substitute age_over_21 for age_over_18"
            ) {
                req.execute(presentmentSource = harness.presentmentSource)
            }
        }

        // OpenID4VP DcqlQuery on mDL:
        // (a) Requesting absent age_over_18 does NOT substitute age_over_21
        val origin = "https://verifier.example.com"
        val nonce = Random.nextBytes(16).toBase64Url()
        val absentDcql = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                "id": "mdl",
                "format": "mso_mdoc",
                "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                "claims": [{ "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"] }]
              }]
            }
            """.trimIndent()
        )
        val absentOid4vpReq = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = absentDcql,
        )
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            OpenID4VP.generateResponse(
                version = OpenID4VP.Version.DRAFT_29,
                preselectedDocuments = emptyList(),
                source = harness.presentmentSource,
                appId = null,
                origin = origin,
                request = absentOid4vpReq,
                requesterIdentities = emptyList(),
            )
        }

        // (b) Requesting 3 exact stored age_over_NN claims via OpenID4VP returns all 3 (not capped at 2)
        val threeExactDcql = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                "id": "mdl",
                "format": "mso_mdoc",
                "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                "claims": [
                  { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_21"] },
                  { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_25"] },
                  { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_60"] }
                ]
              }]
            }
            """.trimIndent()
        )
        val threeExactOid4vpReq = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = origin,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = threeExactDcql,
        )
        val oid4vpResponse = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = harness.presentmentSource,
            appId = null,
            origin = origin,
            request = threeExactOid4vpReq,
            requesterIdentities = emptyList(),
        )
        val vpTokenB64 = oid4vpResponse.response["vp_token"]!!.jsonObject["mdl"]!!.jsonArray[0].jsonPrimitive.content
        val oid4vpDeviceResponse = DeviceResponse.fromDataItem(Cbor.decode(vpTokenB64.fromBase64Url()))
        val handoverInfoDigest = Crypto.digest(
            Algorithm.SHA256,
            Cbor.encode(
                buildCborArray {
                    add(origin)
                    add(nonce)
                    add(Simple.NULL)
                }
            )
        )
        val oid4vpSessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray {
                add("OpenID4VPDCAPIHandover")
                add(handoverInfoDigest)
            }
        }
        oid4vpDeviceResponse.verify(sessionTranscript = oid4vpSessionTranscript)
        val oid4vpDoc = oid4vpDeviceResponse.documents.single()
        assertEquals(
            setOf("age_over_21", "age_over_25", "age_over_60"),
            oid4vpDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.keys
        )
    }

    @Test
    fun verifierRejectsInvalidAgeOverSubstitutionsAndDeviceSignedClaims() = runTest {
        assertTrue(isValidMdlAgeOverSubstitution("age_over_18", "age_over_18", true))
        assertTrue(isValidMdlAgeOverSubstitution("age_over_18", "age_over_21", true))
        assertTrue(isValidMdlAgeOverSubstitution("age_over_18", "age_over_18", false))
        assertTrue(isValidMdlAgeOverSubstitution("age_over_18", "age_over_16", false))

        assertEquals(false, isValidMdlAgeOverSubstitution("age_over_18", "age_over_16", true))
        assertEquals(false, isValidMdlAgeOverSubstitution("age_over_18", "age_over_21", false))
        assertEquals(false, isValidMdlAgeOverSubstitution("age_over_18", "age_in_years", true))
        assertNull(Tstr("true").asBooleanOrNull())

        val now = Clock.System.now().truncateToWholeSeconds()
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey),
            subject = X500Name.fromName("CN=Test IACA,C=US"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
            issuerAltNameUrl = "https://issuer.example.com",
            crlUrl = "https://issuer.example.com/crl",
        )
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = dsKey.publicKey,
            subject = X500Name.fromName("CN=Test mDL DS,C=US"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
        )
        val dsCertChain = X509CertChain(listOf(dsCert, iacaCert))

        val spec = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val mockZkSystem = object : ZkSystem {
            override val name: String = "mock_zk"
            override val systemSpecs: List<ZkSystemSpec> = listOf(spec)
            override fun generateProof(
                zkSystemSpec: ZkSystemSpec,
                document: MdocDocument,
                sessionTranscript: DataItem,
                timestamp: Instant,
            ): ZkDocument = throw UnsupportedOperationException()
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
            ): ZkSystemSpec = spec
        }
        val zkRepo = ZkSystemRepository().add(mockZkSystem)

        val requestAge18 = buildDeviceRequest(
            sessionTranscript = Simple.NULL,
            deviceRequestInfo = SINGLE_DOC_USE_CASE,
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to false)),
                docRequestInfo = DocRequestInfo(zkRequest = ZkRequest(listOf(spec), zkRequired = true)),
            )
        }

        fun buildZkResponse(
            issuerSigned: Map<String, DataItem>,
            deviceSigned: Map<String, DataItem> = emptyMap(),
        ): DataItem {
            val zkDoc = ZkDocument(
                documentData = ZkDocumentData(
                    zkSystemSpecId = spec.id,
                    docType = DrivingLicense.MDL_DOCTYPE,
                    timestamp = now,
                    issuerSigned = mapOf(DrivingLicense.MDL_NAMESPACE to issuerSigned),
                    deviceSigned = if (deviceSigned.isEmpty()) {
                        emptyMap()
                    } else {
                        mapOf(DrivingLicense.MDL_NAMESPACE to deviceSigned)
                    },
                    msoX5chain = dsCertChain,
                ),
                proof = ByteString(1, 2, 3),
            )
            return buildDeviceResponse(
                sessionTranscript = Simple.NULL,
                status = DeviceResponse.STATUS_OK,
            ) {
                addZkDocument(zkDoc)
            }.toDataItem()
        }

        // Valid substitutions succeed: age_over_21 = true, and age_over_16 = false
        assertEquals(
            1,
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(mapOf("age_over_21" to Simple.TRUE)),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            ).size
        )
        assertEquals(
            1,
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(mapOf("age_over_16" to Simple.FALSE)),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            ).size
        )

        // 1. Reject age_over_16 = true for age_over_18 (nn < NN for true)
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(mapOf("age_over_16" to Simple.TRUE)),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            )
        }

        // 2. Reject age_over_21 = false for age_over_18 (nn > NN for false)
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(mapOf("age_over_21" to Simple.FALSE)),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            )
        }

        // 3. Reject non-boolean CBOR Tstr("true")
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(mapOf("age_over_21" to Tstr("true"))),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            )
        }

        // 4. Reject device-signed age_over_21 = true when not present in issuerSigned
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = buildZkResponse(
                    issuerSigned = mapOf("given_name" to Tstr("Erika")),
                    deviceSigned = mapOf("age_over_21" to Simple.TRUE),
                ),
                sessionTranscript = Simple.NULL,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = requestAge18,
            )
        }
    }

    @Test
    fun drivingLicenseMetadataAndConsentClassificationForNonSampleSubstitutedThresholds() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mdl-holder-20",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "age_over_20" to Simple.TRUE,
                    "age_over_22" to Simple.FALSE,
                )
            )
        )

        // 1. Request age_over_19 -> substitutes non-sample age_over_20 = true
        val reqTrue = buildMdlDeviceRequest(
            version = "1.1",
            elements = mapOf("age_over_19" to false)
        )
        val presentmentDataTrue = reqTrue.execute(presentmentSource = harness.presentmentSource)
        val useCaseTrue = presentmentDataTrue.useCases.single()
        assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), useCaseTrue.classifyPurposes(0))
        val matchedTrueClaim = presentmentDataTrue
            .toPresentmentSelection(presentmentDataTrue.defaultSelections())
            .matches.single().claims.values.single() as MdocClaim
        assertEquals("age_over_20", matchedTrueClaim.dataElementName)
        assertEquals("Older than 20 years", matchedTrueClaim.displayName)
        assertNotNull(matchedTrueClaim.attribute)

        val simpleTrue = defaultClassifySimpleConsent(
            requester = Requester(),
            trustedRequesterIdentity = null,
            presentmentData = presentmentDataTrue,
        )
        assertNotNull(simpleTrue)
        assertEquals(SimpleConsentClaim.AgeOver(20), simpleTrue.claim)

        // 2. Request age_over_24 -> substitutes non-sample age_over_22 = false
        val reqFalse = buildMdlDeviceRequest(
            version = "1.1",
            elements = mapOf("age_over_24" to false)
        )
        val presentmentDataFalse = reqFalse.execute(presentmentSource = harness.presentmentSource)
        val simpleFalse = defaultClassifySimpleConsent(
            requester = Requester(),
            trustedRequesterIdentity = null,
            presentmentData = presentmentDataFalse,
        )
        assertNotNull(simpleFalse)
        assertEquals(SimpleConsentClaim.NotAgeOver(22), simpleFalse.claim)
    }
}
