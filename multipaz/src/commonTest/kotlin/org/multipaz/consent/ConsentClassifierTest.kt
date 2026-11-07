package org.multipaz.consent

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.mpzpass.MpzPassClaimMetadata
import org.multipaz.mpzpass.MpzPassMetadata
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.IDPass
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.PhotoID
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
}
