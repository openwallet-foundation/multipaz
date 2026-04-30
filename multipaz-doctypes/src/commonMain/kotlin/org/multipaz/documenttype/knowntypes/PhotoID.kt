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
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.DocumentAttributeSensitivity

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
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return with(DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_PHOTO_ID))) {
        addMdocDocumentType(PHOTO_ID_DOCTYPE)

        // Data elements from ISO/IEC 23220-4 Table C.1 — PhotoID data elements defined by ISO/IEC TS 23220-2
        //
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "family_name",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_FAMILY_NAME),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_FAMILY_NAME),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = SampleData.FAMILY_NAME.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "family_name_viz",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_FAMILY_NAME_VIZ),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_FAMILY_NAME_VIZ),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = SampleData.FAMILY_NAME.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "given_name",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_GIVEN_NAMES),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_GIVEN_NAMES),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = SampleData.GIVEN_NAME.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "given_name_viz",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_GIVEN_NAME_VIZ),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_GIVEN_NAME_VIZ),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = SampleData.GIVEN_NAME.toDataItem()
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
        // NOTE "approximate mask" is not intended to be used for calculation.
        //
        addMdocAttribute(
            type = DocumentAttributeType.Date,   identifier = // TODO: this is a more complex type
            "birth_date",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_DATE_OF_BIRTH),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_DATE_OF_BIRTH),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.TODAY,
            sampleValue = buildCborMap {
                put("birth_date", LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate())
            }
        )
        addMdocAttribute(
            type = DocumentAttributeType.Picture,
            identifier = "portrait",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_PHOTO_OF_HOLDER),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_PHOTO_OF_HOLDER),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE,
            icon = Icon.ACCOUNT_BOX,
            sampleValue = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.Date,
            identifier = "issue_date",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_DATE_OF_ISSUE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_DATE_OF_ISSUE),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.VALIDITY,
            icon = Icon.DATE_RANGE,
            sampleValue = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            type = DocumentAttributeType.Date,
            identifier = "expiry_date",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_DATE_OF_EXPIRY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_DATE_OF_EXPIRY),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.VALIDITY,
            icon = Icon.CALENDAR_CLOCK,
            sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "issuing_authority_unicode",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_ISSUING_AUTHORITY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_ISSUING_AUTHORITY),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.ISSUER,
            icon = Icon.ACCOUNT_BALANCE,
            sampleValue = SampleData.ISSUING_AUTHORITY_PHOTO_ID.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "issuing_country",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_ISSUING_COUNTRY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_ISSUING_COUNTRY),
            mandatory = true,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.ISSUER,
            icon = Icon.ACCOUNT_BALANCE,
            sampleValue = SampleData.ISSUING_COUNTRY.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.Number,
            identifier = "age_in_years",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_AGE_IN_YEARS),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_AGE_IN_YEARS),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
            icon = Icon.TODAY,
            sampleValue = SampleData.AGE_IN_YEARS.toDataItem()
        )
        // If we provision all 99 age_over_NN claims the MSO will be 3886 bytes which exceeds the Longfellow-ZK
        // MSO size limit of ~ 2200 bytes. With these 13 claims, the MSO is 764 bytes which is more manageable.
        val ageThresholdsToProvision = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
        for (age in IntRange(1, 99)) {
            addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_${if (age < 10) "0$age" else "$age"}",
                displayName = "Older than $age years",
                description = "Indication whether the document holder is as old or older than $age",
                mandatory = (age == 18),
                mdocNamespace = ISO_23220_2_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = if (age in ageThresholdsToProvision) {
                    (SampleData.AGE_IN_YEARS >= age).toDataItem()
                } else {
                    null
                }
            )
        }
        addMdocAttribute(
            type = DocumentAttributeType.Number,
            identifier = "age_birth_year",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_YEAR_OF_BIRTH),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_YEAR_OF_BIRTH),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
            icon = Icon.TODAY,
            sampleValue = SampleData.AGE_BIRTH_YEAR.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.Date,
            identifier = "portrait_capture_date",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_PORTRAIT_CAPTURE_DATE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_PORTRAIT_CAPTURE_DATE),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.TODAY,
            sampleValue = LocalDate.parse(SampleData.PORTRAIT_CAPTURE_DATE).toDataItemFullDate()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "birthplace",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_PLACE_OF_BIRTH),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_PLACE_OF_BIRTH),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.BIRTH_PLACE.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "name_at_birth",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_NAME_AT_BIRTH),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_NAME_AT_BIRTH),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_address",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_ADDRESS),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_ADDRESS),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_ADDRESS.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_city",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_CITY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_CITY),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_CITY.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_postal_code",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_POSTAL_CODE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_POSTAL_CODE),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_POSTAL_CODE.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "resident_country",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_COUNTRY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_COUNTRY),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_COUNTRY.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_city_latin1",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_CITY_LATIN1),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_CITY_LATIN1),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
            identifier = "sex",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_SEX),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_SEX),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.EMERGENCY,
            sampleValue = SampleData.SEX_ISO_5218.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "nationality",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_NATIONALITY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_NATIONALITY),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.LANGUAGE,
            sampleValue = SampleData.NATIONALITY.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "document_number",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_DOCUMENT_NUMBER),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_DOCUMENT_NUMBER),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = SampleData.DOCUMENT_NUMBER.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "issuing_subdivision",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_ISSUING_SUBDIVISION),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_ISSUING_SUBDIVISION),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            sensitivity = DocumentAttributeSensitivity.ISSUER,
            icon = Icon.ACCOUNT_BALANCE,
            sampleValue = SampleData.ISSUING_JURISDICTION.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "family_name_latin1",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_FAMILY_NAME_LATIN1),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_FAMILY_NAME_LATIN1),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "given_name_latin1",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_GIVEN_NAMES_LATIN1),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_GIVEN_NAMES_LATIN1),
            mandatory = false,
            mdocNamespace = ISO_23220_2_NAMESPACE,
            icon = Icon.PERSON,
            sampleValue = null
        )

        // Data elements from ISO/IEC 23220-4 Table C.2 — Data elements specifically defined for PhotoID
        //
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "person_id",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_PERSON_ID),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_PERSON_ID),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = SampleData.PERSON_ID.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "birth_country",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_BIRTH_COUNTRY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_BIRTH_COUNTRY),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "birth_state",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_BIRTH_STATE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_BIRTH_STATE),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "birth_city",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_BIRTH_CITY),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_BIRTH_CITY),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "administrative_number",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_ADMINISTRATIVE_NUMBER),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_street",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_STREET),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_STREET),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_STREET.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_house_number",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_HOUSE_NUMBER),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_HOUSE_NUMBER),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_HOUSE_NUMBER.toDataItem()
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "travel_document_type",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_TRAVEL_DOCUMENT_TYPE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_TRAVEL_DOCUMENT_TYPE),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "travel_document_number",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_TRAVEL_DOCUMENT_NUMBER),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_TRAVEL_DOCUMENT_NUMBER),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "travel_document_mrz",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_TRAVEL_DOCUMENT_MRZ),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_TRAVEL_DOCUMENT_MRZ),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "resident_state",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_RESIDENT_STATE),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_RESIDENT_STATE),
            mandatory = false,
            mdocNamespace = PHOTO_ID_NAMESPACE,
            icon = Icon.PLACE,
            sampleValue = SampleData.RESIDENT_STATE.toDataItem()
        )


        // Data elements from ISO/IEC 23220-4 Table C.3 — Data elements defined by ICAO 9303 part 10
        //
        addMdocAttribute(
            type = DocumentAttributeType.String,
            identifier = "version",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_DTC_VC_VERSION),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_DTC_VC_VERSION),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "sod",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_SOD),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_SOD),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg1",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG1),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG1),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg2",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG2),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG2),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg3",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG3),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG3),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg4",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG4),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG4),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg5",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG5),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG5),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg6",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG6),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG6),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg7",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG7),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG7),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg8",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG8),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG8),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg9",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG9),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG9),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg10",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG10),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG10),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg11",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG11),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG11),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg12",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG12),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG12),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg13",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG13),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG13),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg14",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG14),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG14),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg15",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG15),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG15),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )
        addMdocAttribute(
            type = DocumentAttributeType.Blob,
            identifier = "dg16",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_ATTRIBUTE_EMRTD_DG16),
            description = getLocalizedString(GeneratedStringKeys.PHOTO_ID_DESCRIPTION_EMRTD_DG16),
            mandatory = false,
            mdocNamespace = DTC_NAMESPACE,
            icon = Icon.NUMBERS,
            sampleValue = null
        )

        // Finally for the sample requests.
        //
        addSampleRequest(
            id = "age_over_18",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_REQUEST_AGE_OVER_18),
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                )
            ),
        )
        addSampleRequest(
            id = "age_over_18_zkp",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_REQUEST_AGE_OVER_18_ZKP),
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                )
            ),
            mdocUseZkp = true
        )
        addSampleRequest(
            id = "age_over_18_and_portrait",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_REQUEST_AGE_OVER_18_AND_PORTRAIT),
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(
                    "age_over_18" to false,
                    "portrait" to false
                )
            ),
        )
        addSampleRequest(
            id = "mandatory",
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_REQUEST_MANDATORY_DATA_ELEMENTS),
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
            displayName = getLocalizedString(GeneratedStringKeys.PHOTO_ID_REQUEST_ALL_DATA_ELEMENTS),
            mdocDataElements = mapOf(
                ISO_23220_2_NAMESPACE to mapOf(),
                PHOTO_ID_NAMESPACE to mapOf(),
                DTC_NAMESPACE to mapOf()
            )
        )
        }.build()
    }
}
