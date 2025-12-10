package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.LocalDate
import org.multipaz.cbor.buildCborMap
import org.multipaz.documenttype.knowntypes.DrivingLicense.MDL_NAMESPACE

/**
 * PhotoID according to ISO/IEC 23220-4 Annex C.
 *
 * (This is based on ISO/IEC JTC 1/SC 17/WG 4 N 4862 from 2025-12-04)
 */
object PhotoID {
    const val PHOTO_ID_DOCTYPE = "org.iso.23220.photoid.1"
    const val ISO_23220_2_NAMESPACE = "org.iso.23220.1"
    const val PHOTO_ID_NAMESPACE = "org.iso.23220.photoid.1"
    const val DTC_NAMESPACE = "org.iso.23220.dtc.1"

    /**
     * Build the PhotoID Document Type.
     */
    fun getDocumentType(): DocumentType = with(DocumentType.Builder("Photo ID")) {
        addMdocDocumentType(PHOTO_ID_DOCTYPE)

        // Data elements from ISO/IEC 23220-4 Table C.1 — PhotoID data elements defined by ISO/IEC TS 23220-2
        //
        addMdocAttribute(
            DocumentAttributeType.String,
            "family_name",
            "Family Name",
            "Last name, surname, or primary identifier, of the document holder",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            SampleData.FAMILY_NAME.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "family_name_viz",
            "Family Name (VIZ)",
            "Family name as defined for VIZ (visual inspection zone) in ICAO 9303",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            SampleData.FAMILY_NAME.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "given_name",
            "Given Names",
            "First name(s), other name(s), or secondary identifier, of the document holder",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            SampleData.GIVEN_NAME.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "given_name_viz",
            "Given Name (VIZ)",
            "Given name as defined for VIZ (visual inspection zone) in ICAO 9303",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            SampleData.GIVEN_NAME.toDataItem()
        )
        // Note, this is more complicated than mDL and EU PID, according to ISO/IEC 23220-2
        // clause "6.3.1.1.3 Date of birth as either uncertain or approximate, or both"
        //
        // If date of birth includes an unknown part, the following birth_date structure may be used.
        // birth date = {
        //   "birth_date" : full-date,
        //   ? "approximate_mask": tstr
        // }
        // Approximate_mask is an 8 digit flag to denote the location of the mask in YYYYMMDD
        // format. 1 denotes mask.
        //
        // NOTE “approximate mask” is not intended to be used for calculation.
        //
        addMdocAttribute(
            DocumentAttributeType.Date,   // TODO: this is a more complex type
            "birth_date",
            "Date of Birth",
            "Day, month and year on which the document holder was born. If unknown, approximate date of birth",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.TODAY,
            buildCborMap {
                put("birth_date", LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate())
            }
        )
        addMdocAttribute(
            DocumentAttributeType.Picture,
            "portrait",
            "Photo of Holder",
            "A reproduction of the document holder’s portrait",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.ACCOUNT_BOX,
            SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.Date,
            "issue_date",
            "Date of Issue",
            "Date when document was issued",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.DATE_RANGE,
            LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            DocumentAttributeType.Date,
            "expiry_date",
            "Date of Expiry",
            "Date when document expires",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.CALENDAR_CLOCK,
            LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "issuing_authority_unicode",
            "Issuing Authority",
            "Issuing authority name",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.ACCOUNT_BALANCE,
            SampleData.ISSUING_AUTHORITY_PHOTO_ID.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            "issuing_country",
            "Issuing Country",
            "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s country or territory",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.ACCOUNT_BALANCE,
            SampleData.ISSUING_COUNTRY.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.Number,
            "age_in_years",
            "Age in Years",
            "The age of the document holder",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.TODAY,
            SampleData.AGE_IN_YEARS.toDataItem()
        )
        // If we provision all 99 age_over_NN claims the MSO will be 3886 bytes which exceeds the Longfellow-ZK
        // MSO size limit of ~ 2200 bytes. With these 13 claims, the MSO is 764 bytes which is more manageable.
        val ageThresholdsToProvision = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
        for (age in IntRange(1, 99)) {
            addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_${if (age < 10) "0$age" else "$age"}",
                displayName = "Older Than $age Years",
                description = "Indication whether the document holder is as old or older than $age",
                mandatory = (age == 18),
                mdocNamespace = ISO_23220_2_NAMESPACE,
                icon = Icon.TODAY,
                sampleValue = if (age in ageThresholdsToProvision) {
                    (SampleData.AGE_IN_YEARS >= age).toDataItem()
                } else {
                    null
                }
            )
        }
        addMdocAttribute(
            DocumentAttributeType.Number,
            "age_birth_year",
            "Year of Birth",
            "The year when the document holder was born",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.TODAY,
            SampleData.AGE_BIRTH_YEAR.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.Date,
            "portrait_capture_date",
            "Portrait capture date",
            "Date when portrait was taken",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.TODAY,
            LocalDate.parse(SampleData.PORTRAIT_CAPTURE_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "birthplace",
            "Place of Birth",
            "Country and municipality or state/province where the document holder was born",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            SampleData.BIRTH_PLACE.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "name_at_birth",
            "Name at Birth",
            "The name(s) which holder was born",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_address",
            "Resident Address",
            "The place where the document holder resides and/or may be contacted (street/house number, municipality etc.)",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_ADDRESS.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_city",
            "Resident City",
            "The city/municipality (or equivalent) where the holder lives",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_CITY.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_postal_code",
            "Resident Postal Code",
            "The postal code of the document holder",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_POSTAL_CODE.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            "resident_country",
            "Resident Country",
            "The country where the document holder lives",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_COUNTRY.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_city_latin1",
            "Resident City",
            "The city/municipality (or equivalent) where the holder lives",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PLACE,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
            "sex",
            "Sex",
            "document holder’s sex",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.EMERGENCY,
            SampleData.SEX_ISO_5218.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            "nationality",
            "Nationality",
            "Nationality of the document holder",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.LANGUAGE,
            SampleData.NATIONALITY.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "document_number",
            "Document Number",
            "The number assigned or calculated by the issuing authority",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.NUMBERS,
            SampleData.DOCUMENT_NUMBER.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "issuing_subdivision",
            "Issuing Subdivision",
            "Subdivision code as defined in ISO 3166-2, which issued " +
                    "the mobile eID document or within which the issuing " +
                    "authority is located",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.ACCOUNT_BALANCE,
            SampleData.ISSUING_JURISDICTION.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "family_name_latin1",
            "Family Name",
            "Last name, surname, or primary identifier, of the document holder",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "given_name_latin1",
            "Given Names",
            "First name(s), other name(s), or secondary identifier, of the document holder",
            false,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            null
        )

        // Data elements from ISO/IEC 23220-4 Table C.2 — Data elements specifically defined for PhotoID
        //
        addMdocAttribute(
            DocumentAttributeType.String,
            "person_id",
            "Person ID",
            "Person identifier of the Photo ID holder",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.NUMBERS,
            SampleData.PERSON_ID.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            "birth_country",
            "Birth Country",
            "The country where the Photo ID holder was born, as an Alpha-2 country code as specified in ISO 3166-1",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "birth_state",
            "Birth State",
            "The state, province, district, or local area where the Photo ID holder was born",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "birth_city",
            "Birth City",
            "The municipality, city, town, or village where the Photo ID holder was born",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "administrative_number",
            "Administrative Number",
            "A number assigned by the Photo ID issuer for audit control or other purposes",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.NUMBERS,
            SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_street",
            "Resident Street",
            "The name of the street where the Photo ID holder currently resides",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_STREET.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_house_number",
            "Resident House Number",
            "The house number where the Photo ID holder currently resides, including any affix or suffix",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_HOUSE_NUMBER.toDataItem()
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "travel_document_type",
            "Travel Document Type",
            "Identifier of the type of source document, (if associated to or derived from a travel document)",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "travel_document_number",
            "Travel Document Number",
            "The number of the travel document to which the Photo ID is associated (if associated to or " +
                    "derived from a travel document)",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "travel_document_mrz",
            "Travel Document MRZ",
            "Machine readable zone as the text printed on the physical document",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.String,
            "resident_state",
            "Resident State",
            "The state/province/district where the Photo ID holder lives",
            false,
            PHOTO_ID_NAMESPACE,
            Icon.PLACE,
            SampleData.RESIDENT_STATE.toDataItem()
        )


        // Data elements from ISO/IEC 23220-4 Table C.3 — Data elements defined by ICAO 9303 part 10
        //
        addMdocAttribute(
            DocumentAttributeType.String,
            "version",
            "DTC-VC version",
            "Version of the DTC-VC definition",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "sod",
            "eMRTD SOD",
            "Binary data of the eMRTD Document Security Object",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg1",
            "eMRTD DG1",
            "Data Group 1: biographic data (data recorded in MRZ) C",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg2",
            "eMRTD DG2",
            "Data Group 2: reference portrait (encoded face)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg3",
            "eMRTD DG3",
            "Data Group 3: encoded fingers",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg4",
            "eMRTD DG4",
            "Data Group 4: encoded eye(s)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg5",
            "eMRTD DG5",
            "Data Group 5: displayed portrait",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg6",
            "eMRTD DG6",
            "Data Group 6: Reserved for future use",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg7",
            "eMRTD DG7",
            "Data Group 7: Displayed signature or usual mark",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg8",
            "eMRTD DG8",
            "Data Group 8: data feature(s)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg9",
            "eMRTD DG9",
            "Data Group 9: Structure feature(s) ",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg10",
            "eMRTD DG10",
            "Data Group 10: Substance feature(s)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg11",
            "eMRTD DG11",
            "Data Group 11: additional personal detail(s)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg12",
            "eMRTD DG12",
            "Data Group 12: additional document detail(s)",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg13",
            "eMRTD DG13",
            "Data Group 13: optional detail(s) ",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg14",
            "eMRTD DG14",
            "Data Group 14: security options",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg15",
            "eMRTD DG15",
            "Data Group 15: active authentication public key info",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )
        addMdocAttribute(
            DocumentAttributeType.Blob,
            "dg16",
            "eMRTD DG16",
            "Data Group 16: person(s) to notify",
            false,
            DTC_NAMESPACE,
            Icon.NUMBERS,
            null
        )

        // Finally for the sample requests.
        //
        addSampleRequest(
            id = "age_over_18",
            displayName = "Age Over 18",
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                )
            ),
        )
        addSampleRequest(
            id = "age_over_18_zkp",
            displayName = "Age Over 18 (ZKP)",
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                )
            ),
            mdocUseZkp = true
        )
        addSampleRequest(
            id = "age_over_18_and_portrait",
            displayName = "Age Over 18 + Portrait",
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                    "portrait" to false
                )
            ),
        )
        addSampleRequest(
            id = "mandatory",
            displayName = "Mandatory Data Elements",
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "family_name" to false,
                    "given_name" to false,
                    "birth_date" to false,
                    "portrait" to false,
                    "issue_date" to false,
                    "expiry_date" to false,
                    "issuing_authority_unicode" to false,
                    "issuing_country" to false,
                    "age_over_18" to false,
                )
            )
        )
        addSampleRequest(
            id = "full",
            displayName = "All Data Elements",
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(),
                PHOTO_ID_NAMESPACE to mapOf(),
                DTC_NAMESPACE to mapOf()
            )
        )
    }.build()
}
