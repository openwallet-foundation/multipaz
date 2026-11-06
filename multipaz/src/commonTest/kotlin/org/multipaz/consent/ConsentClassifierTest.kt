package org.multipaz.consent

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.mpzpass.MpzPassClaimMetadata
import org.multipaz.mpzpass.MpzPassMetadata
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.Aadhaar
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.IDPass
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentPurposeHintIso18013
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.DigitalPaymentCredentialSdJwt
import org.multipaz.utopia.knowntypes.Loyalty
import org.multipaz.utopia.knowntypes.PingTransaction
import org.multipaz.utopia.knowntypes.UtopiaBoardingPass
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConsentClassifierTest {

    private suspend fun createHarnessWithAllDocTypes(): DocumentStoreTestHarness {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val authorizedNamespaces = listOf(
            ISO_18013_TRANSACTION_DATA_NAMESPACE,
            PaymentTransaction.identifier,
            PingTransaction.identifier,
        )
        harness.provisionStandardDocuments(
            keyAuthorizedNamespaces = authorizedNamespaces
        )

        val allDocTypes = listOf(
            AgeVerification.getDocumentType(),
            IDPass.getDocumentType(),
            DigitalPaymentCredential.getDocumentType(),
            DigitalPaymentCredentialSdJwt.getDocumentType(),
            Loyalty.getDocumentType(),
            UtopiaBoardingPass.getDocumentType(),
            UtopiaMovieTicket.getDocumentType(),
        )
        for (dt in allDocTypes) {
            harness.documentTypeRepository.addDocumentType(dt)
            val doc = harness.documentStore.createDocument(displayName = dt.displayName)
            if (dt.mdocDocumentType != null) {
                dt.createMdocCredentialWithSampleData(
                    document = doc,
                    secureArea = harness.softwareSecureArea,
                    createKeySettings = SoftwareCreateKeySettings.Builder().build(),
                    dsKey = harness.dsKey,
                    signedAt = harness.signedAt,
                    validFrom = harness.validFrom,
                    validUntil = harness.validUntil,
                    deviceKeyAuthorizedNamespaces = authorizedNamespaces,
                )
            }
            if (dt.jsonDocumentType != null) {
                if (dt.jsonDocumentType!!.keyBound) {
                    dt.createKeyBoundSdJwtVcCredentialWithSampleData(
                        document = doc,
                        secureArea = harness.softwareSecureArea,
                        createKeySettings = SoftwareCreateKeySettings.Builder().build(),
                        dsKey = harness.dsKey,
                        signedAt = harness.signedAt,
                        validFrom = harness.validFrom,
                        validUntil = harness.validUntil,
                    )
                } else {
                    dt.createKeylessSdJwtVcCredentialWithSampleData(
                        document = doc,
                        dsKey = harness.dsKey,
                        signedAt = harness.signedAt,
                        validFrom = harness.validFrom,
                        validUntil = harness.validUntil,
                        domain = "sdjwt_keyless",
                    )
                }
            }
        }
        harness.documentTypeRepository.addTransactionType(PaymentTransaction)
        return harness
    }

    private suspend fun executeCannedMdocRequest(
        harness: DocumentStoreTestHarness,
        cannedRequest: SingleDocumentCannedRequest,
    ): PresentmentData {
        val mdocRequest = cannedRequest.mdocRequest!!.let { req ->
            req.copy(
                namespacesToRequest = req.namespacesToRequest.map { ns ->
                    ns.copy(
                        dataElementsToRequest = ns.dataElementsToRequest.filterKeys { de ->
                            de.attribute.sampleValueMdoc != null
                        }
                    )
                }
            )
        }
        val dcql = mdocRequest.toDcql(emptyList())
        val query = DcqlQuery.fromJson(dcql)
        val transactionDataMap = if (cannedRequest.transactionData.isNotEmpty()) {
            cannedRequest.toTransactionDataMap("cred1")
        } else {
            emptyMap()
        }
        return query.execute(
            presentmentSource = harness.presentmentSource,
            transactionDataMap = transactionDataMap,
        )
    }

    private suspend fun executeCannedJsonRequest(
        harness: DocumentStoreTestHarness,
        cannedRequest: SingleDocumentCannedRequest,
    ): PresentmentData {
        val jsonRequest = cannedRequest.jsonRequest!!.let { req ->
            req.copy(
                claimsToRequest = req.claimsToRequest.filter { attr ->
                    attr.sampleValueJson != null
                }
            )
        }
        val dcql = jsonRequest.toDcql()
        val query = DcqlQuery.fromJson(dcql)
        val transactionDataMap = if (cannedRequest.transactionData.isNotEmpty()) {
            cannedRequest.toTransactionDataMap("cred1")
        } else {
            emptyMap()
        }
        return query.execute(
            presentmentSource = harness.presentmentSource,
            transactionDataMap = transactionDataMap,
        )
    }

    @Test
    fun testPurposeHintMapping() {
        assertEquals(
            PresentmentPurpose.IDENTITY_VERIFICATION,
            PresentmentPurposeHintIso18013("org.iso.jtc1.sc17", 1).toPresentmentPurpose()
        )
        assertEquals(
            PresentmentPurpose.DRIVING_PRIVILEGES,
            PresentmentPurposeHintIso18013("org.iso.jtc1.sc17", 2).toPresentmentPurpose()
        )
        assertEquals(
            PresentmentPurpose.AGE_VERIFICATION,
            PresentmentPurposeHintIso18013("org.iso.jtc1.sc17", 3).toPresentmentPurpose()
        )
        assertEquals(
            null,
            PresentmentPurposeHintIso18013("org.iso.jtc1.sc17", 99).toPresentmentPurpose()
        )
        assertEquals(
            null,
            PresentmentPurposeHintIso18013("org.example.custom", 1).toPresentmentPurpose()
        )
    }

    @Test
    fun testDrivingLicenseCannedRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()
        val dt = DrivingLicense.getDocumentType()
        val requests = dt.cannedRequests.associateBy { it.id }

        // Age over 18 / 21 (without portrait)
        for (reqId in listOf("age_over_18", "age_over_21")) {
            val data = executeCannedMdocRequest(harness, requests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), data.useCases[0].classifyPurposes())
        }

        // Age over 18 / 21 + portrait
        for (reqId in listOf("age_over_18_and_portrait", "age_over_21_and_portrait")) {
            val data = executeCannedMdocRequest(harness, requests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), data.useCases[0].classifyPurposes())
        }

        // Mandatory data elements (includes driving_privileges + core identity claims)
        val mandatoryData = executeCannedMdocRequest(harness, requests.getValue("mandatory"))
        assertEquals(
            setOf(PresentmentPurpose.DRIVING_PRIVILEGES, PresentmentPurpose.IDENTITY_VERIFICATION),
            mandatoryData.useCases[0].classifyPurposes()
        )

        // US Transportation (identity + DHS/EDL compliance, no driving_privileges)
        val usTransData = executeCannedMdocRequest(harness, requests.getValue("us-transportation"))
        assertEquals(
            setOf(PresentmentPurpose.IDENTITY_VERIFICATION),
            usTransData.useCases[0].classifyPurposes()
        )

        // Name and address (partially or all stored): lights up both ADDRESS_VERIFICATION and IDENTITY_VERIFICATION
        for (reqId in listOf("name-and-address-partially-stored", "name-and-address-all-stored")) {
            val data = executeCannedMdocRequest(harness, requests.getValue(reqId))
            assertEquals(
                setOf(PresentmentPurpose.ADDRESS_VERIFICATION, PresentmentPurpose.IDENTITY_VERIFICATION),
                data.useCases[0].classifyPurposes()
            )
        }

        // All data elements ("full"): over-collection -> emptySet() purposes
        val fullData = executeCannedMdocRequest(harness, requests.getValue("full"))
        assertEquals(emptySet(), fullData.useCases[0].classifyPurposes())
    }

    @Test
    fun testPhotoIdCannedAndAddressRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()
        val dt = PhotoID.getDocumentType()
        val requests = dt.cannedRequests.associateBy { it.id }

        // Age over 18
        val ageData = executeCannedMdocRequest(harness, requests.getValue("age_over_18"))
        assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), ageData.useCases[0].classifyPurposes())

        // Age over 18 + portrait
        val agePortraitData = executeCannedMdocRequest(harness, requests.getValue("age_over_18_and_portrait"))
        assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), agePortraitData.useCases[0].classifyPurposes())

        // Mandatory (includes age_over_18 + family_name + given_name + birth_date -> IDENTITY_VERIFICATION subsumes AGE_VERIFICATION)
        val mandatoryData = executeCannedMdocRequest(harness, requests.getValue("mandatory"))
        assertEquals(setOf(PresentmentPurpose.IDENTITY_VERIFICATION), mandatoryData.useCases[0].classifyPurposes())

        // Full (All data elements) -> emptySet()
        val fullData = executeCannedMdocRequest(harness, requests.getValue("full"))
        assertEquals(emptySet(), fullData.useCases[0].classifyPurposes())

        // Address-only request on PhotoID -> ADDRESS_VERIFICATION
        val addressOnlyRequest = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(
                    PhotoID.ISO_23220_2_NAMESPACE to mapOf(
                        "resident_address" to false,
                        "resident_city" to false,
                        "resident_postal_code" to false,
                        "resident_country" to false,
                    )
                )
            )
        }
        val addressData = addressOnlyRequest.execute(presentmentSource = harness.presentmentSource)
        assertEquals(setOf(PresentmentPurpose.ADDRESS_VERIFICATION), addressData.useCases[0].classifyPurposes())
    }

    @Test
    fun testEuPersonalIdMdocAndSdJwtRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()
        val dt = EUPersonalID.getDocumentType()
        val requests = dt.cannedRequests.associateBy { it.id }

        for (reqId in listOf("name_and_portrait", "mandatory")) {
            val mdocData = executeCannedMdocRequest(harness, requests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.IDENTITY_VERIFICATION), mdocData.useCases[0].classifyPurposes())

            val jsonData = executeCannedJsonRequest(harness, requests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.IDENTITY_VERIFICATION), jsonData.useCases[0].classifyPurposes())
        }

        // Full (all data elements) on both mdoc and SD-JWT -> emptySet()
        val fullMdocData = executeCannedMdocRequest(harness, requests.getValue("full"))
        assertEquals(emptySet(), fullMdocData.useCases[0].classifyPurposes())

        val fullJsonData = executeCannedJsonRequest(harness, requests.getValue("full"))
        assertEquals(emptySet(), fullJsonData.useCases[0].classifyPurposes())
    }

    @Test
    fun testAgeVerificationAndIdPassRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()

        // AgeVerification doctype
        val avRequests = AgeVerification.getDocumentType().cannedRequests.associateBy { it.id }
        for (reqId in listOf("age_over_18", "age_over_21")) {
            val data = executeCannedMdocRequest(harness, avRequests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), data.useCases[0].classifyPurposes())
        }
        // AgeVerification "full" requests all 13 provisioned age_over_NN claims -> over-collection -> emptySet()
        val avFullData = executeCannedMdocRequest(harness, avRequests.getValue("full"))
        assertEquals(emptySet(), avFullData.useCases[0].classifyPurposes())

        // IDPass doctype
        val idPassRequests = IDPass.getDocumentType().cannedRequests.associateBy { it.id }
        val idPassAge21Portrait = executeCannedMdocRequest(harness, idPassRequests.getValue("age_over_21_and_portrait"))
        assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), idPassAge21Portrait.useCases[0].classifyPurposes())

        val idPassMandatory = executeCannedMdocRequest(harness, idPassRequests.getValue("mandatory"))
        assertEquals(
            setOf(PresentmentPurpose.DRIVING_PRIVILEGES, PresentmentPurpose.IDENTITY_VERIFICATION),
            idPassMandatory.useCases[0].classifyPurposes()
        )

        val idPassFull = executeCannedMdocRequest(harness, idPassRequests.getValue("full"))
        assertEquals(emptySet(), idPassFull.useCases[0].classifyPurposes())
    }

    @Test
    fun testPaymentLoyaltyAndTicketRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()

        // DigitalPaymentCredential (mdoc)
        val paymentRequests = DigitalPaymentCredential.getDocumentType().cannedRequests.associateBy { it.id }
        for (reqId in listOf("payment_sca_minimal", "payment_sca_full", "payment_transaction", "payment_transaction_only_conf")) {
            val data = executeCannedMdocRequest(harness, paymentRequests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.PAYMENT), data.useCases[0].classifyPurposes())
        }

        // DigitalPaymentCredentialSdJwt
        val paymentSdJwtRequests = DigitalPaymentCredentialSdJwt.getDocumentType().cannedRequests.associateBy { it.id }
        for (reqId in listOf("payment_sca_minimal", "payment_sca_full")) {
            val data = executeCannedJsonRequest(harness, paymentSdJwtRequests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.PAYMENT), data.useCases[0].classifyPurposes())
        }

        // Loyalty
        val loyaltyRequests = Loyalty.getDocumentType().cannedRequests.associateBy { it.id }
        for (reqId in listOf("mandatory", "full")) {
            val data = executeCannedMdocRequest(harness, loyaltyRequests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.LOYALTY_VERIFICATION), data.useCases[0].classifyPurposes())
        }

        // Movie Ticket
        val ticketRequests = UtopiaMovieTicket.getDocumentType().cannedRequests.associateBy { it.id }
        for (reqId in listOf("is_parking_prepaid", "ticket_id", "full")) {
            val data = executeCannedJsonRequest(harness, ticketRequests.getValue(reqId))
            assertEquals(setOf(PresentmentPurpose.TICKET_VERIFICATION), data.useCases[0].classifyPurposes())
        }

        // Boarding Pass
        val boardingPassRequest = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = UtopiaBoardingPass.BOARDING_PASS_DOCTYPE,
                nameSpaces = mapOf(
                    UtopiaBoardingPass.BOARDING_PASS_NS to mapOf(
                        "passenger_name" to false,
                        "flight_number" to false,
                        "seat_number" to false,
                        "departure_time" to false,
                    )
                )
            )
        }
        val boardingPassData = boardingPassRequest.execute(presentmentSource = harness.presentmentSource)
        assertEquals(setOf(PresentmentPurpose.TICKET_VERIFICATION), boardingPassData.useCases[0].classifyPurposes())
    }

    @Test
    fun testMultiDocumentRequests() = runTest {
        val harness = createHarnessWithAllDocTypes()
        val multiRequests = wellKnownMultipleDocumentRequests.associateBy { it.id }

        // payment-and-age-over-18: single use-case with 2 credentials -> PAYMENT + AGE_VERIFICATION
        val paymentAndAgeReq = multiRequests.getValue("payment-and-age-over-18")
        val paymentAndAgeQuery = DcqlQuery.fromJson(Json.parseToJsonElement(paymentAndAgeReq.dcqlString).jsonObject)
        val paymentTxJson = PaymentTransaction.parseJson(
            PaymentTransaction.sampleData.getSerializedJson(listOf("payment"))
                .encodeToByteArray().toBase64Url().encodeToByteString()
        )
        val paymentAndAgeData = paymentAndAgeQuery.execute(
            presentmentSource = harness.presentmentSource,
            transactionDataMap = mapOf("payment" to listOf(paymentTxJson))
        )
        assertEquals(
            setOf(PresentmentPurpose.PAYMENT, PresentmentPurpose.AGE_VERIFICATION),
            paymentAndAgeData.useCases[0].classifyPurposes()
        )
        assertEquals(
            setOf(PresentmentPurpose.PAYMENT, PresentmentPurpose.AGE_VERIFICATION),
            paymentAndAgeData.classifyPurposes()
        )
        assertEquals(
            setOf(PresentmentPurpose.PAYMENT, PresentmentPurpose.AGE_VERIFICATION),
            paymentAndAgeData.select(emptyList()).classifyPurposes()
        )

        // mDL-and-PhotoID: single use-case with 2 identity credentials -> IDENTITY_VERIFICATION
        val mdlAndPhotoIdReq = multiRequests.getValue("mDL-and-PhotoID")
        val mdlAndPhotoIdData = DcqlQuery.fromJson(
            Json.parseToJsonElement(mdlAndPhotoIdReq.dcqlString).jsonObject
        ).execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            setOf(PresentmentPurpose.IDENTITY_VERIFICATION),
            mdlAndPhotoIdData.useCases[0].classifyPurposes()
        )
        assertEquals(
            setOf(PresentmentPurpose.IDENTITY_VERIFICATION),
            mdlAndPhotoIdData.classifyPurposes()
        )
    }

    @Test
    fun testPurposeHintsCompatibilityAndMismatch() = runTest {
        val harness = createHarnessWithAllDocTypes()

        // 1. Valid hint: ISO purpose 3 (AGE_VERIFICATION) with age_over_21 + portrait
        val validAgeHintReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = mapOf("org.iso.jtc1.sc17" to 3)
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false,
                    )
                )
            )
        }
        val validAgeHintData = validAgeHintReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(setOf(PresentmentPurpose.AGE_VERIFICATION), validAgeHintData.useCases[0].classifyPurposes())

        // 2. Mismatched hint: ISO purpose 3 (AGE_VERIFICATION) but also requesting family_name (PII) -> emptySet()
        val mismatchedAgeHintReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = mapOf("org.iso.jtc1.sc17" to 3)
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "family_name" to false,
                    )
                )
            )
        }
        val mismatchedAgeHintData = mismatchedAgeHintReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(emptySet(), mismatchedAgeHintData.useCases[0].classifyPurposes())

        // 3. Valid hint: ISO purpose 2 (DRIVING_PRIVILEGES) with mandatory mDL elements (driving_privileges + identity) -> DRIVING_PRIVILEGES
        val validDrivingHintReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = mapOf("org.iso.jtc1.sc17" to 2)
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                        "document_number" to false,
                        "portrait" to false,
                        "driving_privileges" to false,
                    )
                )
            )
        }
        val validDrivingHintData = validDrivingHintReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(setOf(PresentmentPurpose.DRIVING_PRIVILEGES), validDrivingHintData.useCases[0].classifyPurposes())

        // 4. Mismatched hint: ISO purpose 2 (DRIVING_PRIVILEGES) but also requesting resident_address -> emptySet()
        val mismatchedDrivingHintReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = mapOf("org.iso.jtc1.sc17" to 2)
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "driving_privileges" to false,
                        "resident_address" to false,
                    )
                )
            )
        }
        val mismatchedDrivingHintData = mismatchedDrivingHintReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(emptySet(), mismatchedDrivingHintData.useCases[0].classifyPurposes())
    }

    @Test
    fun testUnknownClaim() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "custom-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "unknown_custom_element" to Tstr("CustomValue"),
                    "age_over_21" to true.toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3).toDataItem(),
                )
            )
        )

        val unknownClaimReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "unknown_custom_element" to false,
                    )
                )
            )
        }
        val unknownClaimData = unknownClaimReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(emptySet(), unknownClaimData.useCases[0].classifyPurposes())
    }

    @Test
    fun testCustomDocTypeWithValidAndUnknownClaims() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val customDocType = "org.example.loyalty.1"
        val customDoc = harness.provisionMdoc(
            displayName = "Erika's Mini Mart Loyalty Card",
            docType = customDocType,
            data = mapOf(
                customDocType to listOf(
                    "given_name" to Tstr("Erika"),
                    "family_name" to Tstr("Mustermann"),
                    "merchant_name" to Tstr("Utopia Mini Mart"),
                    "membership_number" to Tstr("24601"),
                    "tier" to Tstr("Gold"),
                    "valid_from" to Tstr("2026-01-01"),
                    "valid_until" to Tstr("2027-01-01"),
                    "undocumented_claim" to Tstr("Secret"),
                )
            )
        )
        customDoc.edit {
            mpzPassMetadata = MpzPassMetadata(
                isoMdocClaimsMetadata = mapOf(
                    customDocType to mapOf(
                        "given_name" to MpzPassClaimMetadata(displayName = mapOf("en" to "Given Name")),
                        "family_name" to MpzPassClaimMetadata(displayName = mapOf("en" to "Family Name")),
                        "merchant_name" to MpzPassClaimMetadata(displayName = mapOf("en" to "Merchant Name")),
                        "membership_number" to MpzPassClaimMetadata(displayName = mapOf("en" to "Membership Number")),
                        "tier" to MpzPassClaimMetadata(displayName = mapOf("en" to "Tier")),
                        "valid_from" to MpzPassClaimMetadata(
                            displayName = mapOf("en" to "Valid From"),
                            type = DocumentAttributeType.Date,
                        ),
                        "valid_until" to MpzPassClaimMetadata(
                            displayName = mapOf("en" to "Valid Until"),
                            type = DocumentAttributeType.Date,
                        ),
                    )
                )
            )
        }

        val trustedRequester = Requester(
            requesterIdentities = listOf(Iso18013RequesterIdentity(harness.dsKey.certChain)),
            origin = "https://verifier.multipaz.org",
        )
        val trustedIdentity = TrustedRequesterIdentity(
            identity = trustedRequester.requesterIdentities.first(),
            trustMetadata = TrustMetadata(displayName = "Multipaz Verifier"),
        )
        val unverifiedWebRequester = Requester(
            requesterIdentities = listOf(Iso18013RequesterIdentity(harness.dsKey.certChain)),
            origin = "https://verifier.multipaz.org",
        )

        // 1. Valid request for schema-defined claims on a custom doctype -> empty purposes, NOT unusual
        val validDcql = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                {
                  "credentials": [
                    {
                      "id": "mdoc",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.example.loyalty.1"
                      },
                      "claims": [
                        { "path": ["org.example.loyalty.1", "given_name"] },
                        { "path": ["org.example.loyalty.1", "family_name"] },
                        { "path": ["org.example.loyalty.1", "merchant_name"] },
                        { "path": ["org.example.loyalty.1", "membership_number"] },
                        { "path": ["org.example.loyalty.1", "tier"] },
                        { "path": ["org.example.loyalty.1", "valid_from"] },
                        { "path": ["org.example.loyalty.1", "valid_until"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            ).jsonObject
        )
        val validData = validDcql.execute(presentmentSource = harness.presentmentSource)
        assertEquals(emptySet(), defaultClassifyPurposes(trustedRequester, trustedIdentity, validData))
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(trustedRequester, trustedIdentity, validData)
        )
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.WARNING,
                setOf(ConsentRiskReason.UNTRUSTED_REQUESTER, ConsentRiskReason.PII_REQUESTED),
            ),
            defaultClassifyRisk(unverifiedWebRequester, null, validData)
        )

        // 2. Request including a claim not in the custom doctype schema (attribute == null) -> HIGH_RISK (UNUSUAL_REQUEST)
        val unknownClaimDcql = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                {
                  "credentials": [
                    {
                      "id": "mdoc",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.example.loyalty.1"
                      },
                      "claims": [
                        { "path": ["org.example.loyalty.1", "given_name"] },
                        { "path": ["org.example.loyalty.1", "undocumented_claim"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            ).jsonObject
        )
        val unknownClaimData = unknownClaimDcql.execute(presentmentSource = harness.presentmentSource)
        assertEquals(emptySet(), defaultClassifyPurposes(trustedRequester, trustedIdentity, unknownClaimData))
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.HIGH_RISK, setOf(ConsentRiskReason.UNUSUAL_REQUEST)),
            defaultClassifyRisk(trustedRequester, trustedIdentity, unknownClaimData)
        )
    }

    @Test
    fun testSimpleConsentClassification() = runTest {
        val harness = createHarnessWithAllDocTypes()
        val proximityRequester = Requester()
        val webRequester = Requester(origin = "https://wine.example.com")
        val appRequester = Requester(appId = "com.example.wine")

        val dt = DrivingLicense.getDocumentType()
        val requests = dt.cannedRequests.associateBy { it.id }

        // 1. age_over_21 without portrait -> qualifies in both proximity and web/app
        val age21Data = executeCannedMdocRequest(harness, requests.getValue("age_over_21"))
        val simpleAge21Prox = assertNotNull(
            defaultClassifySimpleConsent(proximityRequester, null, age21Data)
        )
        assertEquals(SimpleConsentClaim.AgeOver(21), simpleAge21Prox.claim)
        val simpleAge21Web = assertNotNull(
            defaultClassifySimpleConsent(webRequester, null, age21Data)
        )
        assertEquals(SimpleConsentClaim.AgeOver(21), simpleAge21Web.claim)

        // 2. age_over_21 + portrait -> qualifies in proximity, disqualified on web/app
        val age21PortraitData = executeCannedMdocRequest(harness, requests.getValue("age_over_21_and_portrait"))
        val simpleAge21PortraitProx = assertNotNull(
            defaultClassifySimpleConsent(proximityRequester, null, age21PortraitData)
        )
        assertEquals(SimpleConsentClaim.AgeOverAndPortrait(21), simpleAge21PortraitProx.claim)
        assertNull(defaultClassifySimpleConsent(webRequester, null, age21PortraitData))
        assertNull(defaultClassifySimpleConsent(appRequester, null, age21PortraitData))

        // 3. age_in_years and age_birth_year (+ validity/issuer metadata or portrait) -> qualifies
        val ageInYearsReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_in_years" to false,
                        "expiry_date" to false,
                        "issuing_country" to false,
                    )
                )
            )
        }
        val ageInYearsData = ageInYearsReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.AgeInYears,
            assertNotNull(defaultClassifySimpleConsent(webRequester, null, ageInYearsData)).claim
        )

        val ageInYearsPortraitReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_in_years" to false,
                        "portrait" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                        "issuing_country" to false,
                        "issuing_authority" to false,
                        "un_distinguishing_sign" to false,
                        "issuing_jurisdiction" to false,
                    ),
                    DrivingLicense.AAMVA_NAMESPACE to mapOf(
                        "DHS_compliance" to false,
                        "DHS_temporary_lawful_status" to false,
                        "hazmat_endorsement_expiration_date" to false,
                        "audit_information" to false,
                        "aamva_version" to false,
                    ),
                )
            )
        }
        val ageInYearsPortraitData = ageInYearsPortraitReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.AgeInYearsAndPortrait,
            assertNotNull(defaultClassifySimpleConsent(proximityRequester, null, ageInYearsPortraitData)).claim
        )
        assertNull(defaultClassifySimpleConsent(webRequester, null, ageInYearsPortraitData))

        val ageBirthYearReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_birth_year" to false,
                    )
                )
            )
        }
        val ageBirthYearData = ageBirthYearReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.AgeBirthYear,
            assertNotNull(defaultClassifySimpleConsent(webRequester, null, ageBirthYearData)).claim
        )

        val ageBirthYearPortraitReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_birth_year" to false,
                        "portrait" to false,
                    )
                )
            )
        }
        val ageBirthYearPortraitData = ageBirthYearPortraitReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.AgeBirthYearAndPortrait,
            assertNotNull(defaultClassifySimpleConsent(proximityRequester, null, ageBirthYearPortraitData)).claim
        )
        assertNull(defaultClassifySimpleConsent(webRequester, null, ageBirthYearPortraitData))

        // 4. False age_over claim (SampleData has age_over_65 = false) -> NotAgeOver / NotAgeOverAndPortrait
        val age65Req = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_65" to false,
                    )
                )
            )
        }
        val age65Data = age65Req.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.NotAgeOver(65),
            assertNotNull(defaultClassifySimpleConsent(proximityRequester, null, age65Data)).claim
        )
        assertEquals(
            SimpleConsentClaim.NotAgeOver(65),
            assertNotNull(defaultClassifySimpleConsent(webRequester, null, age65Data)).claim
        )

        val age65PortraitReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_65" to false,
                        "portrait" to false,
                    )
                )
            )
        }
        val age65PortraitData = age65PortraitReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(
            SimpleConsentClaim.NotAgeOverAndPortrait(65),
            assertNotNull(defaultClassifySimpleConsent(proximityRequester, null, age65PortraitData)).claim
        )
        assertNull(defaultClassifySimpleConsent(webRequester, null, age65PortraitData))

        // 5. Multiple age claims (age_over_18 + age_over_21) -> disqualified
        val twoAgesReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "age_over_21" to false,
                    )
                )
            )
        }
        val twoAgesData = twoAgesReq.execute(presentmentSource = harness.presentmentSource)
        assertNull(defaultClassifySimpleConsent(proximityRequester, null, twoAgesData))

        // 6. intentToRetain = true -> disqualified
        val retainAgeReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to true,
                    )
                )
            )
        }
        val retainAgeData = retainAgeReq.execute(presentmentSource = harness.presentmentSource)
        assertNull(defaultClassifySimpleConsent(proximityRequester, null, retainAgeData))

        // 7. PII request (mandatory mDL) -> disqualified
        val mandatoryData = executeCannedMdocRequest(harness, requests.getValue("mandatory"))
        assertNull(defaultClassifySimpleConsent(proximityRequester, null, mandatoryData))
    }

    @Test
    fun testMultiDocAlternativeAgeRequest() = runTest {
        val harness = createHarnessWithAllDocTypes()
        // Request from Multipaz Wallet to check for age >= 65 across multiple document types.
        val requestHex = """
            a46776657273696f6e63312e316b646f63526571756573747386a26c6974656d7352657175657374
            d818590128a367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6a6e616d
            65537061636573a1716f72672e69736f2e31383031332e352e31a468706f727472616974f46b6167
            655f6f7665725f3635f47169737375696e675f617574686f72697479f46f69737375696e675f636f
            756e747279f46b72657175657374496e666fa177616c7465726e617469766544617461456c656d65
            6e747381a270726571756573746564456c656d656e7482716f72672e69736f2e31383031332e352e
            316b6167655f6f7665725f363576616c7465726e6174697665456c656d656e745365747382818271
            6f72672e69736f2e31383031332e352e316c6167655f696e5f79656172738182716f72672e69736f
            2e31383031332e352e316a62697274685f646174656a726561646572417574688443a10126a11821
            59025d30820259308201e0a003020102021100d39f810da13c0e2650d871caa89f2246300a06082a
            8648ce3d0403033033310b300906035504060c0255533124302206035504030c1b4d756c74697061
            7a204964656e7469747920526561646572204341301e170d3236313130333135353131335a170d32
            36313230343035353633305a30253123302106035504030c1a4d756c746970617a2057616c6c6574
            20526561646572204b65793059301306072a8648ce3d020106082a8648ce3d03010703420004435d
            da2cc950f054042644da40a8120a5938986130c745febb0fa4174f9367fdcdb67671a7d04e0e586
            8e0da9facf97f9349cc2edf55816573a48356bb0e4fc3a381e23081df301f0603551d2304183016
            8014cfa4af87907312962e4d7a17646acc1c45719b21300e0603551d0f0101ff0404030207803015
            0603551d250101ff040b3009060728818c5d05010630560603551d1f044f304d304ba049a0478645
            68747470733a2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f756e646174696f6e
            2d6c6162732f6964656e746974792d63726564656e7469616c2f63726c301e0603551d1104173015
            821377616c6c65742e6d756c746970617a2e6f7267301d0603551d0e04160414c5f6bbb7fd7acbe7
            86e9892160db6496b2be769e300a06082a8648ce3d04030303670030640230029cdbee6fa985b456
            9d45a461ed69980c5d4a2b490b619d044b5723e71ebedde5bebff841ee4d916ca46565a0a0636c02
            303036e422365ac233d21779ec92adbca5182afb41686c14bf52b1c125428356263bde0ecd6d05bf
            b0ddfa1b4941b0ef76f65840a1ef423e0473becf535906de35d218117d4b9b83dd267942f464b0fb
            289e0f28e4c3851555dab7f7d39cdfcf6afdce7d097de129b6e52902e2ec45a1cb5dedb6a26c6974
            656d7352657175657374d818590266a367646f6354797065776f72672e69736f2e32333232302e70
            686f746f69642e316a6e616d65537061636573a16f6f72672e69736f2e32333232302e31a468706f
            727472616974f46b6167655f6f7665725f3635f47169737375696e675f617574686f72697479f46f
            69737375696e675f636f756e747279f46b72657175657374496e666fa177616c7465726e61746976
            6544617461456c656d656e747383a270726571756573746564456c656d656e74826f6f72672e6973
            6f2e32333232302e316b6167655f6f7665725f363576616c7465726e6174697665456c656d656e74
            536574738281826f6f72672e69736f2e32333232302e316c6167655f696e5f796561727381826f6f
            72672e69736f2e32333232302e316a62697274685f64617465a270726571756573746564456c656d
            656e74826f6f72672e69736f2e32333232302e317169737375696e675f617574686f726974797661
            6c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e32333232302e31
            781969737375696e675f617574686f726974795f756e69636f646581826f6f72672e69736f2e3233
            3232302e31781869737375696e675f617574686f726974795f6c6174696e31a27072657175657374
            6564456c656d656e74826f6f72672e69736f2e32333232302e316f69737375696e675f636f756e74
            727976616c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e323332
            32302e317769737375696e675f636f756e7472795f756e69636f646581826f6f72672e69736f2e32
            333232302e317669737375696e675f636f756e7472795f6c6174696e316a72656164657241757468
            8443a10126a1182159025d30820259308201e0a003020102021100d39f810da13c0e2650d871caa8
            9f2246300a06082a8648ce3d0403033033310b300906035504060c0255533124302206035504030c
            1b4d756c746970617a204964656e7469747920526561646572204341301e170d3236313130333135
            353131335a170d3236313230343035353633305a30253123302106035504030c1a4d756c74697061
            7a2057616c6c657420526561646572204b65793059301306072a8648ce3d020106082a8648ce3d03
            010703420004435dda2cc950f054042644da40a8120a5938986130c745febb0fa4174f9367fdcdb
            67671a7d04e0e5868e0da9facf97f9349cc2edf55816573a48356bb0e4fc3a381e23081df301f06
            03551d23041830168014cfa4af87907312962e4d7a17646acc1c45719b21300e0603551d0f0101ff
            04040302078030150603551d250101ff040b3009060728818c5d05010630560603551d1f044f304d
            304ba049a047864568747470733a2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f
            756e646174696f6e2d6c6162732f6964656e746974792d63726564656e7469616c2f63726c301e06
            03551d1104173015821377616c6c65742e6d756c746970617a2e6f7267301d0603551d0e04160414
            c5f6bbb7fd7acbe786e9892160db6496b2be769e300a06082a8648ce3d0403030367003064023002
            9cdbee6fa985b4569d45a461ed69980c5d4a2b490b619d044b5723e71ebedde5bebff841ee4d916c
            a46565a0a0636c02303036e422365ac233d21779ec92adbca5182afb41686c14bf52b1c125428356
            263bde0ecd6d05bfb0ddfa1b4941b0ef76f658408824d9fed81aaac3054f6ad5c9996fcce8427a45
            8cdbbd7ba2ad30f337848d033907623255e935802a8dc2aa657c5026da96f0ede15824e7822b612d
            0d6dea2aa26c6974656d7352657175657374d8185880a267646f63547970657765752e6575726f70
            612e65632e657564692e7069642e316a6e616d65537061636573a17765752e6575726f70612e6563
            2e657564692e7069642e31a468706f727472616974f46a62697274685f64617465f4716973737569
            6e675f617574686f72697479f46f69737375696e675f636f756e747279f46a726561646572417574
            688443a10126a1182159025d30820259308201e0a003020102021100d39f810da13c0e2650d871ca
            a89f2246300a06082a8648ce3d0403033033310b300906035504060c025553312430220603550403
            0c1b4d756c746970617a204964656e7469747920526561646572204341301e170d32363131303331
            35353131335a170d3236313230343035353633305a30253123302106035504030c1a4d756c746970
            617a2057616c6c657420526561646572204b65793059301306072a8648ce3d020106082a8648ce3d
            03010703420004435dda2cc950f054042644da40a8120a5938986130c745febb0fa4174f9367fdcd
            b67671a7d04e0e5868e0da9facf97f9349cc2edf55816573a48356bb0e4fc3a381e23081df301f06
            03551d23041830168014cfa4af87907312962e4d7a17646acc1c45719b21300e0603551d0f0101ff
            04040302078030150603551d250101ff040b3009060728818c5d05010630560603551d1f044f304d
            304ba049a047864568747470733a2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f
            756e646174696f6e2d6c6162732f6964656e746974792d63726564656e7469616c2f63726c301e06
            03551d1104173015821377616c6c65742e6d756c746970617a2e6f7267301d0603551d0e04160414
            c5f6bbb7fd7acbe786e9892160db6496b2be769e300a06082a8648ce3d0403030367003064023002
            9cdbee6fa985b4569d45a461ed69980c5d4a2b490b619d044b5723e71ebedde5bebff841ee4d916c
            a46565a0a0636c02303036e422365ac233d21779ec92adbca5182afb41686c14bf52b1c125428356
            263bde0ecd6d05bfb0ddfa1b4941b0ef76f65840a3fcfe35354e35f502bdf9755f4882a86f3ecc7e
            241b6a3977ccf04ce83179c70dca6b22190b1588ba83674495c70fb9109b64d46aec9d0bcdcb04fc
            a8c01d01a26c6974656d7352657175657374d81859014da367646f63547970656e75726e3a657564
            693a7069643a316a6e616d65537061636573a1615fa46f73646a777476635f70696374757265f471
            73646a777476635f626972746864617465f4781973646a777476635f69737375696e675f61757468
            6f72697479f47773646a777476635f69737375696e675f636f756e747279f46b7265717565737449
            6e666fa269646f63466f726d61746964632b73642d6a7774781c64617461456c656d656e74496465
            6e7469666965724d617070696e67a46f73646a777476635f70696374757265816770696374757265
            7173646a777476635f6269727468646174658169626972746864617465781973646a777476635f69
            737375696e675f617574686f72697479817169737375696e675f617574686f726974797773646a77
            7476635f69737375696e675f636f756e747279816f69737375696e675f636f756e7472796a726561
            646572417574688443a10126a1182159025d30820259308201e0a003020102021100d39f810da13c
            0e2650d871caa89f2246300a06082a8648ce3d0403033033310b300906035504060c025553312430
            2206035504030c1b4d756c746970617a204964656e7469747920526561646572204341301e170d32
            36313130333135353131335a170d3236313230343035353633305a30253123302106035504030c1a
            4d756c746970617a2057616c6c657420526561646572204b65793059301306072a8648ce3d020106
            082a8648ce3d03010703420004435dda2cc950f054042644da40a8120a5938986130c745febb0fa
            4174f9367fdcdb67671a7d04e0e5868e0da9facf97f9349cc2edf55816573a48356bb0e4fc3a381
            e23081df301f0603551d23041830168014cfa4af87907312962e4d7a17646acc1c45719b21300e06
            03551d0f0101ff04040302078030150603551d250101ff040b3009060728818c5d05010630560603
            551d1f044f304d304ba049a047864568747470733a2f2f6769746875622e636f6d2f6f70656e7761
            6c6c65742d666f756e646174696f6e2d6c6162732f6964656e746974792d63726564656e7469616c
            2f63726c301e0603551d1104173015821377616c6c65742e6d756c746970617a2e6f7267301d0603
            551d0e04160414c5f6bbb7fd7acbe786e9892160db6496b2be769e300a06082a8648ce3d04030303
            670030640230029cdbee6fa985b4569d45a461ed69980c5d4a2b490b619d044b5723e71ebedde5be
            bff841ee4d916ca46565a0a0636c02303036e422365ac233d21779ec92adbca5182afb41686c14bf
            52b1c125428356263bde0ecd6d05bfb0ddfa1b4941b0ef76f658404e897628da40ad1e252e347b8d
            7ae536d8555ee1c2ec7694949c30553558d0b7308473ee934f57485136639ce12f9f5d54a840c4e5
            7eb4466d916716bbace831a26c6974656d7352657175657374d81858efa367646f63547970657669
            6e2e676f762e75696461692e616164686161722e316a6e616d65537061636573a176696e2e676f76
            2e75696461692e616164686161722e31a26d5265736964656e74496d616765f46a41676541626f76
            653635f46b72657175657374496e666fa177616c7465726e617469766544617461456c656d656e74
            7381a270726571756573746564456c656d656e748276696e2e676f762e75696461692e6161646861
            61722e316a41676541626f7665363576616c7465726e6174697665456c656d656e74536574738181
            8276696e2e676f762e75696461692e616164686161722e3163446f626a7265616465724175746884
            43a10126a1182159025d30820259308201e0a003020102021100d39f810da13c0e2650d871caa89f
            2246300a06082a8648ce3d0403033033310b300906035504060c0255533124302206035504030c1b
            4d756c746970617a204964656e7469747920526561646572204341301e170d323631313033313535
            3131335a170d3236313230343035353633305a30253123302106035504030c1a4d756c746970617a
            2057616c6c657420526561646572204b65793059301306072a8648ce3d020106082a8648ce3d0301
            0703420004435dda2cc950f054042644da40a8120a5938986130c745febb0fa4174f9367fdcdb67
            671a7d04e0e5868e0da9facf97f9349cc2edf55816573a48356bb0e4fc3a381e23081df301f0603
            551d23041830168014cfa4af87907312962e4d7a17646acc1c45719b21300e0603551d0f0101ff04
            040302078030150603551d250101ff040b3009060728818c5d05010630560603551d1f044f304d30
            4ba049a047864568747470733a2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f75
            6e646174696f6e2d6c6162732f6964656e746974792d63726564656e7469616c2f63726c301e0603
            551d1104173015821377616c6c65742e6d756c746970617a2e6f7267301d0603551d0e04160414c5
            f6bbb7fd7acbe786e9892160db6496b2be769e300a06082a8648ce3d04030303670030640230029c
            dbee6fa985b4569d45a461ed69980c5d4a2b490b619d044b5723e71ebedde5bebff841ee4d916ca4
            6565a0a0636c02303036e422365ac233d21779ec92adbca5182afb41686c14bf52b1c12542835626
            3bde0ecd6d05bfb0ddfa1b4941b0ef76f6584022e61915fce24684848f1a7cf900dcf828d6f9a253
            98a44e3dbdb22317717fbd51523fb014b4e2a3be06eadb6b2719b3a50c0e96e84b6c960763c7fa36
            e71bafa26c6974656d7352657175657374d81859012ea367646f6354797065781a636f6d2e676f6f
            676c652e77616c6c65742e6964636172642e316a6e616d65537061636573a1716f72672e69736f2e
            31383031332e352e31a468706f727472616974f46b6167655f6f7665725f3635f47169737375696e
            675f617574686f72697479f46f69737375696e675f636f756e747279f46b72657175657374496e66
            6fa177616c7465726e617469766544617461456c656d656e747381a270726571756573746564456c
            656d656e7482716f72672e69736f2e31383031332e352e316b6167655f6f7665725f363576616c74
            65726e6174697665456c656d656e7453657473828182716f72672e69736f2e31383031332e352e31
            6c6167655f696e5f79656172738182716f72672e69736f2e31383031332e352e316a62697274685f
            646174656a726561646572417574688443a10126a1182159025d30820259308201e0a00302010202
            1100d39f810da13c0e2650d871caa89f2246300a06082a8648ce3d0403033033310b300906035504
            060c0255533124302206035504030c1b4d756c746970617a204964656e7469747920526561646572
            204341301e170d3236313130333135353131335a170d3236313230343035353633305a3025312330
            2106035504030c1a4d756c746970617a2057616c6c657420526561646572204b6579305930130607
            2a8648ce3d020106082a8648ce3d03010703420004435dda2cc950f054042644da40a8120a59389
            86130c745febb0fa4174f9367fdcdb67671a7d04e0e5868e0da9facf97f9349cc2edf55816573a4
            8356bb0e4fc3a381e23081df301f0603551d23041830168014cfa4af87907312962e4d7a17646acc
            1c45719b21300e0603551d0f0101ff04040302078030150603551d250101ff040b3009060728818c
            5d05010630560603551d1f044f304d304ba049a047864568747470733a2f2f6769746875622e636f
            6d2f6f70656e77616c6c65742d666f756e646174696f6e2d6c6162732f6964656e746974792d6372
            6564656e7469616c2f63726c301e0603551d1104173015821377616c6c65742e6d756c746970617a
            2e6f7267301d0603551d0e04160414c5f6bbb7fd7acbe786e9892160db6496b2be769e300a06082
            a8648ce3d04030303670030640230029cdbee6fa985b4569d45a461ed69980c5d4a2b490b619d04
            4b5723e71ebedde5bebff841ee4d916ca46565a0a0636c02303036e422365ac233d21779ec92adbc
            a5182afb41686c14bf52b1c125428356263bde0ecd6d05bfb0ddfa1b4941b0ef76f65840e25e0d2c
            4b744f6be9ad571773bb547956ae6cc6fad964797e369aa6df6e33a2014ed6f0e4e01e20dc69918c
            0840e1d3a72394afd76a07d42fff6d062cd398aa7164657669636552657175657374496e666fd818
            5831a168757365436173657381a2696d616e6461746f7279f56c646f63756d656e74536574738681
            00810181028103810481056d72656164657241757468416c6c818443a10126a1182159025d308202
            59308201e0a003020102021100d39f810da13c0e2650d871caa89f2246300a06082a8648ce3d0403
            033033310b300906035504060c0255533124302206035504030c1b4d756c746970617a204964656e
            7469747920526561646572204341301e170d3236313130333135353131335a170d32363132303430
            35353633305a30253123302106035504030c1a4d756c746970617a2057616c6c6574205265616465
            72204b65793059301306072a8648ce3d020106082a8648ce3d03010703420004435dda2cc950f054
            042644da40a8120a5938986130c745febb0fa4174f9367fdcdb67671a7d04e0e5868e0da9facf97
            f9349cc2edf55816573a48356bb0e4fc3a381e23081df301f0603551d23041830168014cfa4af87
            907312962e4d7a17646acc1c45719b21300e0603551d0f0101ff04040302078030150603551d2501
            01ff040b3009060728818c5d05010630560603551d1f044f304d304ba049a047864568747470733a
            2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f756e646174696f6e2d6c6162732f
            6964656e746974792d63726564656e7469616c2f63726c301e0603551d1104173015821377616c6c
            65742e6d756c746970617a2e6f7267301d0603551d0e04160414c5f6bbb7fd7acbe786e9892160db
            6496b2be769e300a06082a8648ce3d04030303670030640230029cdbee6fa985b4569d45a461ed69
            980c5d4a2b490b619d044b5723e71ebedde5bebff841ee4d916ca46565a0a0636c02303036e42236
            5ac233d21779ec92adbca5182afb41686c14bf52b1c125428356263bde0ecd6d05bfb0ddfa1b4941
            b0ef76f65840e5c852f4bef6c5a0e0a37917d3217c90c0529b75cb6b0e1a39e7318ea82abb638e1d
            2ae01f7c2dbe64ccf964aea6c7dd7702b28dd9f8c64ef06a0370f2bdfa7a
        """.trimIndent().replace("\n", "")
        val sessionTranscriptHex = """
            83d8185858a20063312e30018201d818584ba40102200121582003b944c483208495d138f967797a
            16eaa9fca68aa84d573c71ccbc4048a9e97f2258208ba6ca1cf6b7f02e293af6227b994b73beed69
            8fa199ec90a46d154fb9965da2d818584ba4010220012158203d46d73b5d652c5d1522c9961141eb
            aa73845faafa0f18366ae1be0fc6d4dc102258201a0722e77c8aad7e4d1253ac26dd689793a35517
            4270953f5ddf4ff74035c2888258c291020f487315d10209616301013001046d646f631c1e580469
            736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e3001
            8201d818584ba40102200121582003b944c483208495d138f967797a16eaa9fca68aa84d573c71cc
            bc4048a9e97f2258208ba6ca1cf6b7f02e293af6227b994b73beed698fa199ec90a46d154fb9965d
            a25a200b016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c
            01071601ffa100189b588391020a487215d102046163010130001c1e060a69736f2e6f72673a3138
            3031333a726561646572656e676167656d656e746d646f63726561646572a10063312e305a201d01
            6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c0011075a03
            7ad42648a62550efed16bb6c0e50071601ffa100189b
        """.trimIndent().replace("\n", "")
        val deviceRequest = DeviceRequest.fromDataItem(
            Cbor.decode(requestHex.fromHex())
        )
        deviceRequest.verifyReaderAuthentication(
            sessionTranscript = Cbor.decode(sessionTranscriptHex.fromHex())
        )
        val presentmentData = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val simpleConsent = assertNotNull(
            defaultClassifySimpleConsent(Requester(), null, presentmentData)
        )
        assertEquals(SimpleConsentClaim.NotAgeOverAndPortrait(65), simpleConsent.claim)
        assertEquals("mDL", simpleConsent.credential.document.displayName)

        // Selecting an EU PID solution (which falls back to birth_date) is disqualified from SimpleConsent
        assertNull(
            defaultClassifySimpleConsent(
                requester = Requester(),
                trustedRequesterIdentity = null,
                presentmentData = presentmentData,
                selections = listOf(listOf(3)),
            )
        )
    }
}
