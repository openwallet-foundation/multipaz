package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.datetime.LocalDate

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
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Certificate of residency")
            .addMdocDocumentType(DOCTYPE)
            .addJsonDocumentType(type = VCT, keyBound = true)
            .addAttribute(
                DocumentAttributeType.String,
                "family_name",
                "Family name",
                "Current last name(s), surname(s), or primary identifier of the COR holder",
                true,
                NAMESPACE,
                Icon.PERSON,
                SampleData.FAMILY_NAME.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "given_name",
                "Given names",
                "Current first name(s), other name(s), or secondary identifier of the COR holder",
                true,
                NAMESPACE,
                Icon.PERSON,
                SampleData.GIVEN_NAME.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                "Date of birth",
                "Day, month, and year on which the COR holder was born. If unknown, approximate date of birth.",
                true,
                NAMESPACE,
                Icon.TODAY,
                LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.Boolean,
                "age_over_18",
                "Older than 18",
                "Age over 18?",
                false,
                NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_18.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Boolean,
                "age_over_21",
                "Older than 21",
                "Age over 21?",
                false,
                NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_21.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "arrival_date",
                "Date of arrival",
                "Day, month, and year on which the COR holder arrived to the EU.",
                false,
                NAMESPACE,
                Icon.DATE_RANGE,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_address",
                "Resident address",
                "The full address of the place where the COR holder currently resides and/or may be contacted (street/house number, municipality etc.)",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "resident_country",
                "Resident country",
                "The country where the user currently resides, as an Alpha-2 country code as specified in ISO 3166-1",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_COUNTRY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_state",
                "Resident state",
                "The state, province, district, or local area where the user currently resides.",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_STATE.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_city",
                "Resident city",
                "The city where the COR holder currently resides",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_postal_code",
                "Resident postal code",
                "The postal code of the place where the COR holder currently resides",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_street",
                "Resident street",
                "The name of the street where the user currently resides.",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_STREET.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "resident_house_number",
                "Resident house number",
                "The house number where the user currently resides, including any affix or suffix",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_HOUSE_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "birth_place",
                "Place of birth",
                "The place where the COR holder was born.",
                false,
                NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_CITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "gender",
                "Gender",
                "COR holder’s gender",
                false,
                NAMESPACE,
                Icon.EMERGENCY,
                SampleData.SEX_ISO_5218.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "nationality",
                "Nationality",
                "Alpha-2 country code as specified in ISO 3166-1, representing the nationality of the user.",
                true,
                NAMESPACE,
                Icon.LANGUAGE,
                SampleData.NATIONALITY.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "issuance_date",
                "Date of issue",
                "Date (and possibly time) when the COR was issued.",
                true,
                NAMESPACE,
                Icon.DATE_RANGE,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                "Date of expiry",
                "Date (and possibly time) when the COR will expire.",
                true,
                NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "issuing_authority",
                "Issuing authority",
                "Name of the administrative authority that has issued this COR instance, or the " +
                        "ISO 3166 Alpha-2 country code of the respective Member State if there is" +
                        "no separate authority authorized to issue CORs.",
                true,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_AUTHORITY_EU_PID.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "document_number",
                "Document number",
                "A number for the COR, assigned by the COR Provider.",
                false,
                NAMESPACE,
                Icon.NUMBERS,
                SampleData.DOCUMENT_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "administrative_number",
                "Administrative number",
                "A number assigned by the COR Provider for audit control or other purposes.",
                false,
                NAMESPACE,
                Icon.NUMBERS,
                SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.String,
                "issuing_jurisdiction",
                "Issuing jurisdiction",
                "Country subdivision code of the jurisdiction that issued the COR, as defined in " +
                        "ISO 3166-2:2020, Clause 8. The first part of the code SHALL be the same " +
                        "as the value for issuing_country.",
                false,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_JURISDICTION.toDataItem()
            )
            .addAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "issuing_country",
                "Issuing country",
                "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s " +
                        "country or territory",
                true,
                NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_COUNTRY.toDataItem()
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = "Age over 18",
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                jsonClaims = listOf("age_over_18")
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = "Mandatory data elements",
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
                displayName = "All data elements",
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf()
                ),
                jsonClaims = listOf()
            )
            .build()
    }
}