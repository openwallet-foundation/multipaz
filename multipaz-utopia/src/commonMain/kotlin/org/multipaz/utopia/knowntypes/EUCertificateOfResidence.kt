package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.datetime.LocalDate
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.Options
import org.multipaz.documenttype.knowntypes.SampleData

/**
 * Object containing the metadata of the EU Certificate of Residency (COR) document.
 *
 * TODO: see if this document type still exists and how exactly it is defined. This
 * definition is ad hoc and added to facilitate interoperability testing.
 */
object EUCertificateOfResidence {
    const val DOCTYPE = "eu.europa.ec.eudi.cor.1"
    const val NAMESPACE = "eu.europa.ec.eudi.cor.1"
    const val VCT = "https://example.eudi.ec.europa.eu/cor/1"

    /**
     * Build the EU Certificate of Residency Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_CERTIFICATE_OF_RESIDENCY))
            .addMdocDocumentType(DOCTYPE)
            .addJsonDocumentType(type = VCT, keyBound = true)
            .addAttribute(
                DocumentAttributeType.String,
                "family_name",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_FAMILY_NAME),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_FAMILY_NAME),
                true,
                NAMESPACE,
                Icon.PERSON,
                SampleData.FAMILY_NAME.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "given_name",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_GIVEN_NAMES),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_GIVEN_NAMES),
                true,
                NAMESPACE,
                Icon.PERSON,
                SampleData.GIVEN_NAME.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_BIRTH),
                true,
                NAMESPACE,
                Icon.TODAY,
                LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.Boolean,
                "age_over_18",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_OLDER_THAN_18),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_OLDER_THAN_18),
                false,
                NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_18.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Boolean,
                "age_over_21",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_OLDER_THAN_21),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_OLDER_THAN_21),
                false,
                NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_21.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "arrival_date",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_ARRIVAL),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_ARRIVAL),
                false,
                NAMESPACE,
                Icon.DATE_RANGE,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_address",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_ADDRESS),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_ADDRESS),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "resident_country",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_COUNTRY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_COUNTRY),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_COUNTRY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_state",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_STATE),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_STATE),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_STATE.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_city",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_CITY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_CITY),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_postal_code",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_POSTAL_CODE),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_street",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_STREET),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_STREET),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_STREET.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_house_number",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_HOUSE_NUMBER),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_HOUSE_NUMBER),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_HOUSE_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "birth_place",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_PLACE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_PLACE_OF_BIRTH),
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "gender",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_GENDER),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_GENDER),
                false,
                NAMESPACE,
                Icon.EMERGENCY,
                SampleData.SEX_ISO_5218.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "nationality",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_NATIONALITY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_NATIONALITY),
                true,
                NAMESPACE,
                Icon.LANGUAGE,
                SampleData.NATIONALITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "issuance_date",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_ISSUE),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_ISSUE),
                true,
                NAMESPACE,
                Icon.DATE_RANGE,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_EXPIRY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_EXPIRY),
                true,
                NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "issuing_authority",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_AUTHORITY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_AUTHORITY),
                true,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_AUTHORITY_EU_PID.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "document_number",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DOCUMENT_NUMBER),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DOCUMENT_NUMBER),
                false,
                NAMESPACE,
                Icon.NUMBERS,
                SampleData.DOCUMENT_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "administrative_number",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ADMINISTRATIVE_NUMBER),
                false,
                NAMESPACE,
                Icon.NUMBERS,
                SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "issuing_jurisdiction",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_JURISDICTION),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_JURISDICTION),
                false,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_JURISDICTION.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "issuing_country",
                getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_COUNTRY),
                getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_COUNTRY),
                true,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_COUNTRY.toDataItem()
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.COR_REQUEST_AGE_OVER_18),
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                jsonClaims = listOf("age_over_18")
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = getLocalizedString(GeneratedStringKeys.COR_REQUEST_MANDATORY_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                        "age_over_18" to false,
                        "issuance_date" to false,
                        "expiry_date" to false,
                        "issuing_authority" to false,
                        "issuing_country" to false
                    )
                ),
                jsonClaims = listOf(
                    "family_name",
                    "given_name",
                    "birth_date",
                    "age_over_18",
                    "issuance_date",
                    "expiry_date",
                    "issuing_authority",
                    "issuing_country"
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.COR_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf()
                ),
                jsonClaims = listOf()
            )
            .build()
    }
}
