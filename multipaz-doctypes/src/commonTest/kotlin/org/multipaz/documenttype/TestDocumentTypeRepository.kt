package org.multipaz.documenttype

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.knowntypes.DrivingLicense
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.documenttype.knowntypes.Aadhaar
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.IDPass
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.VaccinationDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestDocumentTypeRepository {
    companion object {
        // No timezone DB in Kotlin/JS so just use offsets.
        //
        // NOTE: This doesn't account for DST nor does it account for political events like
        //   the timezone offset change. So please don't copy any of this around.
        private const val TZ_AMERICA_LOS_ANGELES = "UTC-08:00"
        private const val TZ_AMERICA_NEW_YORK = "UTC-05:00"
        private const val TZ_EUROPE_COPENHAGEN = "UTC+01:00"
        private const val TZ_AUSTRALIA_BRISBANE = "UTC+10:00"
        private const val TZ_PACIFIC_HONOLULU = "UTC-10:00"
    }

    @Test
    fun testDocumentTypeRepositoryDrivingLicense() {
        val documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
        val documentTypes = documentTypeRepository.documentTypes
        assertEquals(1, documentTypes.count())
        assertEquals("Driving license", documentTypes[0].displayName)
        assertEquals("org.iso.18013.5.1.mDL", documentTypes[0].mdocDocumentType?.docType)
        assertEquals(
            "org.iso.18013.5.1",
            documentTypes[0].mdocDocumentType?.namespaces?.iterator()?.next()?.key
        )
        assertEquals(
            "org.iso.18013.5.1",
            documentTypes[0].mdocDocumentType?.namespaces?.iterator()?.next()?.value?.namespace
        )
        assertEquals(
            DocumentAttributeType.String,
            documentTypes[0].mdocDocumentType?.namespaces?.get("org.iso.18013.5.1")?.dataElements?.get("family_name")?.attribute?.type
        )
        assertEquals(
            "org.iso.18013.5.1.aamva",
            documentTypes[0].mdocDocumentType?.namespaces?.values?.toList()?.last()?.namespace
        )
        assertEquals(
            DocumentAttributeType.ComplexType,
            documentTypes[0].mdocDocumentType?.namespaces?.get("org.iso.18013.5.1.aamva")?.dataElements?.get("domestic_driving_privileges")?.attribute?.type
        )
        val mdlElements = documentTypes[0].mdocDocumentType?.namespaces?.get("org.iso.18013.5.1")?.dataElements
        assertNotNull(mdlElements)
        assertEquals(
            DocumentAttributeType.DateTime,
            mdlElements["portrait_capture_date"]?.attribute?.type
        )
        assertEquals(
            Tagged.DATE_TIME_STRING,
            (mdlElements["portrait_capture_date"]?.attribute?.sampleValueMdoc as? Tagged)?.tagNumber
        )
        assertEquals(
            DocumentAttributeType.String,
            mdlElements["resident_street"]?.attribute?.type
        )
        assertEquals(false, mdlElements["resident_street"]?.mandatory)
        assertEquals(
            DocumentAttributeType.Boolean,
            mdlElements["expiry_date_after_validuntil"]?.attribute?.type
        )
        assertEquals(false, mdlElements["expiry_date_after_validuntil"]?.mandatory)
        assertEquals(
            DocumentAttributeSensitivity.VALIDITY,
            mdlElements["expiry_date_after_validuntil"]?.attribute?.sensitivity
        )
        assertEquals(
            Simple.TRUE,
            mdlElements["expiry_date_after_validuntil"]?.attribute?.sampleValueMdoc
        )
    }

    @Test
    fun testAadhaarDocumentType() {
        val documentType = Aadhaar.getDocumentType()
        val mdoc = documentType.mdocDocumentType
        assertNotNull(mdoc)

        assertEquals("Aadhaar", documentType.displayName)
        assertEquals(Aadhaar.AADHAAR_DOCTYPE, mdoc.docType)
        assertTrue(mdoc.namespaces.containsKey(Aadhaar.AADHAAR_NAMESPACE))

        val aadhaarNamespace = mdoc.namespaces[Aadhaar.AADHAAR_NAMESPACE]
        assertNotNull(aadhaarNamespace)

        listOf(
            "resident_name",
            "enrolment_number",
            "mobile",
            "email",
            "dob",
        ).forEach { dataElementName ->
            assertTrue(
                aadhaarNamespace.dataElements.containsKey(dataElementName),
                "Expected data element '$dataElementName' in aadhaar namespace"
            )
        }
    }

    @Test
    fun testSupportsPortraitImageEquivalence() {
        assertEquals(
            MdocPortraitEquivalenceData(DrivingLicense.MDL_NAMESPACE, "portrait"),
            DrivingLicense.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence
        )
        assertEquals(
            MdocPortraitEquivalenceData(PhotoID.ISO_23220_2_NAMESPACE, "portrait"),
            PhotoID.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence
        )
        assertEquals(
            MdocPortraitEquivalenceData(EUPersonalID.EUPID_NAMESPACE, "portrait"),
            EUPersonalID.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence
        )
        assertEquals(
            MdocPortraitEquivalenceData(DrivingLicense.MDL_NAMESPACE, "portrait"),
            IDPass.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence
        )
        assertEquals(
            MdocPortraitEquivalenceData(Aadhaar.AADHAAR_NAMESPACE, "resident_image"),
            Aadhaar.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence
        )
        assertNull(VaccinationDocument.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence)
        assertNull(AgeVerification.getDocumentType().mdocDocumentType?.supportsPortraitImageEquivalence)
    }

    @Test
    fun testPortraitEquivalenceCannedRequests() {
        data class TestCase(
            val dt: DocumentType,
            val portraitNamespace: String,
            val portraitDataElement: String,
            val bothRequestId: String = "age_over_18_and_portrait_or_portrait_equivalence",
            val chvOnlyRequestId: String = "age_over_18_and_portrait_equivalence",
            val baseClaimCount: Int = 1,
        )
        val testCases = listOf(
            TestCase(DrivingLicense.getDocumentType(), DrivingLicense.MDL_NAMESPACE, "portrait"),
            TestCase(PhotoID.getDocumentType(), PhotoID.ISO_23220_2_NAMESPACE, "portrait"),
            TestCase(
                dt = EUPersonalID.getDocumentType(),
                portraitNamespace = EUPersonalID.EUPID_NAMESPACE,
                portraitDataElement = "portrait",
                bothRequestId = "name_and_portrait_or_portrait_equivalence",
                chvOnlyRequestId = "name_and_portrait_equivalence",
                baseClaimCount = 2,
            ),
            TestCase(IDPass.getDocumentType(), DrivingLicense.MDL_NAMESPACE, "portrait"),
            TestCase(Aadhaar.getDocumentType(), Aadhaar.AADHAAR_NAMESPACE, "resident_image"),
        )
        for (tc in testCases) {
            val requestWithBoth = tc.dt.cannedRequests.find {
                it.id == tc.bothRequestId
            }!!.mdocRequest!!
            val dcqlWithBoth = requestWithBoth.toDcql(emptyList())
            val credWithBoth = dcqlWithBoth["credentials"]!!.jsonArray[0].jsonObject
            val claimsWithBoth = credWithBoth["claims"]!!.jsonArray
            assertEquals(
                tc.baseClaimCount + 2,
                claimsWithBoth.size,
                "Document type ${tc.dt.displayName} claims size mismatch"
            )
            val pePortrait = claimsWithBoth.find {
                it.jsonObject["id"]?.jsonPrimitive?.content == "pe_portrait"
            }!!.jsonObject
            val pePortraitPath = pePortrait["path"]!!.jsonArray
            assertEquals(tc.portraitNamespace, pePortraitPath[0].jsonPrimitive.content)
            assertEquals(tc.portraitDataElement, pePortraitPath[1].jsonPrimitive.content)

            val peChv1 = claimsWithBoth.find {
                it.jsonObject["id"]?.jsonPrimitive?.content == "pe_chv1"
            }!!.jsonObject
            val peChv1Path = peChv1["path"]!!.jsonArray
            assertEquals(ISO_23220_5_CHV_1_NAMESPACE, peChv1Path[0].jsonPrimitive.content)
            assertEquals(ISO_23220_5_CHV_1_DATA_ELEMENT, peChv1Path[1].jsonPrimitive.content)

            val claimSets = credWithBoth["claim_sets"]!!.jsonArray
            assertEquals(2, claimSets.size)

            val requestWithChvOnly = tc.dt.cannedRequests.find {
                it.id == tc.chvOnlyRequestId
            }!!.mdocRequest!!
            val dcqlWithChvOnly = requestWithChvOnly.toDcql(emptyList())
            val credWithChvOnly = dcqlWithChvOnly["credentials"]!!.jsonArray[0].jsonObject
            val claimsWithChvOnly = credWithChvOnly["claims"]!!.jsonArray
            assertEquals(
                tc.baseClaimCount + 1,
                claimsWithChvOnly.size,
                "Document type ${tc.dt.displayName} claims size mismatch"
            )
            assertNull(credWithChvOnly["claim_sets"])
        }
    }

    @Test
    fun testRenderValueAsString() {
        val ct = DrivingLicense.getDocumentType()
        val mdlNs = ct.mdocDocumentType!!.namespaces[DrivingLicense.MDL_NAMESPACE]!!
        val aamvaNs = ct.mdocDocumentType!!.namespaces[DrivingLicense.AAMVA_NAMESPACE]!!

        // CredentialAttributeType.Boolean
        assertEquals(
            "true",
            mdlNs.dataElements["age_over_18"]?.renderValue(Simple.TRUE)
        )
        assertEquals(
            "false",
            mdlNs.dataElements["age_over_18"]?.renderValue(Simple.FALSE)
        )
        assertEquals(
            "yes",
            mdlNs.dataElements["age_over_18"]?.renderValue(
                Simple.TRUE, trueFalseStrings = Pair("no", "yes"))
        )
        assertEquals(
            "no",
            mdlNs.dataElements["age_over_18"]?.renderValue(
                Simple.FALSE, trueFalseStrings = Pair("no", "yes"))
        )

        // CredentialAttributeType.String
        assertEquals(
            "Erika",
            mdlNs.dataElements["given_name"]?.renderValue(Tstr("Erika"))
        )

        // CredentialAttributeType.Number
        assertEquals(
            "180",
            mdlNs.dataElements["height"]?.renderValue(Uint(180UL))
        )

        // CredentialAttributeType.IntegerOptions
        assertEquals(
            "Donor",
            aamvaNs.dataElements["organ_donor"]?.renderValue(Uint(1UL))
        )
        // If we don't know the enumerated value, check we render the raw value.
        assertEquals(
            "2",
            aamvaNs.dataElements["organ_donor"]?.renderValue(Uint(2UL))
        )

        // CredentialAttributeType.StringOptions
        assertEquals(
            "Asian or Pacific Islander",
            aamvaNs.dataElements["race_ethnicity"]?.renderValue(Tstr("AP"))
        )
        // If we don't know the enumerated value, check we render the raw value.
        assertEquals(
            "AddedLater",
            aamvaNs.dataElements["race_ethnicity"]?.renderValue(Tstr("AddedLater"))
        )

        // CredentialAttributeType.Picture
        assertEquals(
            "0 bytes",
            mdlNs.dataElements["portrait"]?.renderValue(Bstr(byteArrayOf()))
        )
        assertEquals(
            "1 byte",
            mdlNs.dataElements["portrait"]?.renderValue(Bstr(byteArrayOf(1)))
        )
        assertEquals(
            "3 bytes",
            mdlNs.dataElements["portrait"]?.renderValue(Bstr(byteArrayOf(1, 2, 3)))
        )

        // CredentialAttributeType.DateTime - supports both tdate, full-date, and ISO/IEC 23220-2 maps
        assertEquals(
            "1976-02-03T06:30:00",
            MdocDataElement(
                DocumentAttribute(
                    type = DocumentAttributeType.DateTime,
                    identifier = "",
                    displayName = "",
                    description = ""
                ),
                false
            ).renderValue(
                Instant.parse("1976-02-03T05:30:00Z").toDataItemDateTimeString(),
                timeZone = TimeZone.of(TZ_EUROPE_COPENHAGEN)
            )
        )
        assertEquals(
            "1976-02-03T06:30:00",
            MdocDataElement(
                DocumentAttribute(
                    type = DocumentAttributeType.DateTime,
                    identifier = "",
                    displayName = "",
                    description = ""
                ),
                false
            ).renderValue(
                buildCborMap {
                    put(
                        "birth_date",
                        Instant.parse("1976-02-03T05:30:00Z").toDataItemDateTimeString()
                    )
                },
                timeZone = TimeZone.of(TZ_EUROPE_COPENHAGEN)
            )
        )
        // ... if using a full-date we render the point in time as midnight. The timezone
        // isn't taken into account, check a couple of different timezones
        for (zoneId in listOf(TZ_EUROPE_COPENHAGEN, TZ_AUSTRALIA_BRISBANE, TZ_PACIFIC_HONOLULU)) {
            assertEquals(
                "1976-02-03T00:00:00",
                MdocDataElement(
                    DocumentAttribute(
                        type = DocumentAttributeType.DateTime,
                        identifier = "",
                        displayName = "",
                        description = ""
                    ),
                    false
                ).renderValue(
                    LocalDate.parse("1976-02-03").toDataItemFullDate(),
                    timeZone = TimeZone.of(zoneId)
                )
            )
            assertEquals(
                "1976-02-03T00:00:00",
                MdocDataElement(
                    DocumentAttribute(
                        type = DocumentAttributeType.DateTime,
                        identifier = "",
                        displayName = "",
                        description = ""
                    ),
                    false
                ).renderValue(
                    buildCborMap {
                        put("birth_date", LocalDate.parse("1976-02-03").toDataItemFullDate())
                    },
                    timeZone = TimeZone.of(zoneId)
                )
            )
        }

        // CredentialAttributeType.Date - supports both tdate, full-date, and ISO/IEC 23220-2 maps
        // for tdate the timezone is taken into account, for full-date it isn't.
        assertEquals(
            "1976-02-03",
            mdlNs.dataElements["birth_date"]?.renderValue(
                Instant.parse("1976-02-03T05:30:00Z").toDataItemDateTimeString(),
                timeZone = TimeZone.of(TZ_EUROPE_COPENHAGEN)
            )
        )
        assertEquals(
            "1976-02-02",
            mdlNs.dataElements["birth_date"]?.renderValue(
                Instant.parse("1976-02-03T05:30:00Z").toDataItemDateTimeString(),
                timeZone = TimeZone.of(TZ_AMERICA_LOS_ANGELES)
            )
        )
        assertEquals(
            "1976-02-03",
            mdlNs.dataElements["birth_date"]?.renderValue(
                LocalDate.parse("1976-02-03").toDataItemFullDate(),
                timeZone = TimeZone.of(TZ_EUROPE_COPENHAGEN)
            )
        )
        assertEquals(
            "1976-02-03",
            mdlNs.dataElements["birth_date"]?.renderValue(
                LocalDate.parse("1976-02-03").toDataItemFullDate(),
                timeZone = TimeZone.of(TZ_AMERICA_LOS_ANGELES)
            )
        )
        assertEquals(
            "1976-02-03",
            mdlNs.dataElements["birth_date"]?.renderValue(
                buildCborMap {
                    put(
                        "birth_date",
                        Instant.parse("1976-02-03T05:30:00Z").toDataItemDateTimeString()
                    )
                },
                timeZone = TimeZone.of(TZ_AMERICA_NEW_YORK)
            )
        )
        assertEquals(
            "1976-02-03",
            mdlNs.dataElements["birth_date"]?.renderValue(
                buildCborMap {
                    put("birth_date", LocalDate.parse("1976-02-03").toDataItemFullDate())
                },
                timeZone = TimeZone.of(TZ_AMERICA_NEW_YORK)
            )
        )

        // CredentialAttributeType.ComplexType
        val drivingPrivileges = buildCborArray {
            addCborMap {
                put("vehicle_category_code", "A")
                put("issue_date", Tagged(1004, Tstr("2018-08-09")))
                put("expiry_date", Tagged(1004, Tstr("2024-10-20")))
            }
            addCborMap {
                put("vehicle_category_code", "B")
                put("issue_date", Tagged(1004, Tstr("2017-02-23")))
                put("expiry_date", Tagged(1004, Tstr("2024-10-20")))
            }
        }
        // Note, this isn't very nice but it's not clear we can do any better at this point,
        // fitting complex stuff like this into a string is going to be a mess no matter how
        // you slice or dice it.
        //
        // We could add the ability to add dedicated renderers at credential type registration
        // time and those could return rich text, e.g. Markup. It's not clear that this would
        // be an advantage of the app doing this itself. Maybe...
        //
        assertEquals(
            "[{\"vehicle_category_code\": \"A\", \"issue_date\": 1004(\"2018-08-09\"), " +
                    "\"expiry_date\": 1004(\"2024-10-20\")}, {\"vehicle_category_code\": \"B\", " +
                    "\"issue_date\": 1004(\"2017-02-23\"), \"expiry_date\": 1004(\"2024-10-20\")}]",
            mdlNs.dataElements["driving_privileges"]?.renderValue(drivingPrivileges)
        )


        // Now check that it does the right thing if a value is passed which
        // isn't expected by the document type
        assertEquals(
            "3 bytes",
            mdlNs.dataElements["portrait"]?.renderValue(Bstr(byteArrayOf(1, 2, 3)))
        )

    }

    // This test exists to ensure that DocumentAttribute's hashCode(), toString(), and equals() doesn't
    // stack overflow when using embedded attributes such as in the JSON version of EU PID.
    @Test
    fun testEUPidClaimsNoOverflow() {
        val documentType = EUPersonalID.getDocumentType()
        documentType.jsonDocumentType!!.claims.forEach { (key, value) ->
            val hashCode = value.hashCode()
            val string = value.toString()
            val equals = value.equals(value)
        }
    }

    @Test
    fun testEUPidDocumentType() {
        val documentType = EUPersonalID.getDocumentType()
        val mdoc = documentType.mdocDocumentType
        assertNotNull(mdoc)
        val pidNamespace = mdoc.namespaces[EUPersonalID.EUPID_NAMESPACE]
        assertNotNull(pidNamespace)

        val expectedMdocElements = setOf(
            "family_name",
            "given_name",
            "birth_date",
            "family_name_birth",
            "given_name_birth",
            "place_of_birth",
            "resident_address",
            "resident_country",
            "resident_state",
            "resident_city",
            "resident_postal_code",
            "resident_street",
            "sex",
            "nationality",
            "issuance_date",
            "expiry_date",
            "issuing_authority",
            "document_number",
            "personal_administrative_number",
            "issuing_jurisdiction",
            "issuing_country",
            "portrait",
            "email_address",
            "mobile_phone_number"
        )
        assertEquals(expectedMdocElements, pidNamespace.dataElements.keys)
        assertEquals(
            DocumentAttributeType.ComplexType,
            pidNamespace.dataElements["place_of_birth"]?.attribute?.type
        )
        assertEquals(true, pidNamespace.dataElements["place_of_birth"]?.mandatory)

        val mandatoryMdocElements = pidNamespace.dataElements
            .filter { it.value.mandatory }
            .keys
        val expectedMandatoryMdocElements = setOf(
            "family_name",
            "given_name",
            "birth_date",
            "place_of_birth",
            "nationality",
            "expiry_date",
            "issuing_authority",
            "issuing_country"
        )
        assertEquals(expectedMandatoryMdocElements, mandatoryMdocElements)

        val mandatoryRequest = documentType.cannedRequests.find { it.id == "mandatory" }
        assertNotNull(mandatoryRequest)
        val requestedMdocElements = mandatoryRequest.mdocRequest!!.namespacesToRequest
            .find { it.namespace == EUPersonalID.EUPID_NAMESPACE }!!
            .dataElementsToRequest.keys.map { it.attribute.identifier }.toSet()
        assertEquals(expectedMandatoryMdocElements, requestedMdocElements)

        val json = documentType.jsonDocumentType
        assertNotNull(json)
        assertNotNull(json.getDocumentAttribute("place_of_birth"))
        assertNotNull(json.getDocumentAttribute("place_of_birth.country"))
        assertNotNull(json.getDocumentAttribute("place_of_birth.region"))
        assertNotNull(json.getDocumentAttribute("place_of_birth.locality"))
        assertNotNull(json.getDocumentAttribute("address.house_number"))
    }

}
