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
import org.multipaz.documenttype.DocumentAttributeSensitivity

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
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.FAMILY_NAME.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_GIVEN_NAMES),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.GIVEN_NAME.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                identifier = "birth_date",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_BIRTH),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_OLDER_THAN_18),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_OLDER_THAN_18),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_18.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_21",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_OLDER_THAN_21),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_OLDER_THAN_21),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_21.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                identifier = "arrival_date",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_ARRIVAL),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_ARRIVAL),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.DATE_RANGE,
                sampleValueMdoc = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_address",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_ADDRESS),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_ADDRESS),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "resident_country",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_COUNTRY),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_COUNTRY.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_state",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_STATE),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_STATE),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_STATE.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_city",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_CITY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_CITY),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_postal_code",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_POSTAL_CODE),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_street",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_STREET),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_STREET),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_STREET.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_house_number",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_RESIDENT_HOUSE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_RESIDENT_HOUSE_NUMBER),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_HOUSE_NUMBER.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_place",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_PLACE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_PLACE_OF_BIRTH),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "gender",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_GENDER),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_GENDER),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValueMdoc = SampleData.SEX_ISO_5218.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "nationality",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_NATIONALITY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_NATIONALITY),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                icon = Icon.LANGUAGE,
                sampleValueMdoc = SampleData.NATIONALITY.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issuance_date",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_ISSUE),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_ISSUE),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.DATE_RANGE,
                sampleValueMdoc = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                identifier = "expiry_date",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DATE_OF_EXPIRY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DATE_OF_EXPIRY),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValueMdoc = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_authority",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_AUTHORITY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_AUTHORITY),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_AUTHORITY_EU_PID.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "document_number",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_DOCUMENT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_DOCUMENT_NUMBER),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.DOCUMENT_NUMBER.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "administrative_number",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ADMINISTRATIVE_NUMBER),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_jurisdiction",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_JURISDICTION),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_JURISDICTION),
                mandatory = false,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_JURISDICTION.toDataItem()
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "issuing_country",
                displayName = getLocalizedString(GeneratedStringKeys.COR_ATTRIBUTE_ISSUING_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.COR_DESCRIPTION_ISSUING_COUNTRY),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_COUNTRY.toDataItem()
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
