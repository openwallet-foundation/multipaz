package org.multipaz.documenttype.knowntypes

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Object containing the metadata of the German ID Document Type.
 *
 * For now, this is a copy of EUPersonaID.
 *
 * TODO: read this (and other) VCTs for their URLs.
 */
object GermanPersonalID {
    const val EUPID_VCT = "https://example.bmi.bund.de/credential/pid/1.0"

    /**
     * Build the EU Personal ID Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("German personal ID")
            .addJsonDocumentType(type = EUPID_VCT, keyBound = true)
            .addJsonAttribute(
                DocumentAttributeType.String,
                "family_name",
                "Family name",
                "Current last name(s), surname(s), or primary identifier of the PID holder",
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "given_name",
                "Given names",
                "Current first name(s), other name(s), or secondary identifier of the PID holder",
                Icon.PERSON,
                JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "birthdate",
                "Date of birth",
                "Day, month, and year on which the PID holder was born. If unknown, approximate date of birth.",
                Icon.TODAY,
                JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Number,
                "age_in_years",
                "Age in years",
                "The age of the PID holder in years",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_IN_YEARS)
            )
            .addJsonAttribute(
                DocumentAttributeType.Number,
                "age_birth_year",
                "Year of birth",
                "The year when the PID holder was born",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_BIRTH_YEAR)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "12",
                "Older than 12",
                "Age over 12?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "14",
                "Older than 14",
                "Age over 14?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "16",
                "Older than 16",
                "Age over 16?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_16)
            )
            // TODO: nest in age_equal_or_over object
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "18",
                "Older than 18",
                "Age over 18?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_18)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "21",
                "Older than 21",
                "Age over 21?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_21)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "65",
                "Older than 65",
                "Age over 65?",
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_65)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_family_name",
                "Family name at birth",
                "Last name(s), surname(s), or primary identifier of the PID holder at birth",
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME_BIRTH)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_place",
                "Place of birth",
                "Country and municipality or state/province where the PID holder was born",
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_PLACE)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "birth_country",
                "Country of birth",
                "The country where the PID User was born, as an Alpha-2 country code as specified in ISO 3166-1",
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_COUNTRY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_state",
                "State of birth",
                "The state, province, district, or local area where the PID User was born",
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_STATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_city",
                "City of birth",
                "The municipality, city, town, or village where the PID User was born",
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_CITY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "street_address",
                "Resident address",
                "The full address of the place where the PID holder currently resides and/or may be contacted (street/house number, municipality etc.)",
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_ADDRESS)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "locality",
                "Resident city",
                "The city where the PID holder currently resides",
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_CITY)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "country",
                "Resident country",
                "The country where the PID User currently resides, as an Alpha-2 country code as specified in ISO 3166-1",
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_COUNTRY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "postal_code",
                "Resident postal code",
                "The postal code of the place where the PID holder currently resides",
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_POSTAL_CODE)
            )
            .addJsonAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "gender",
                "Gender",
                "PID holder’s gender",
                Icon.EMERGENCY,
                JsonPrimitive(SampleData.SEX_ISO_5218)
            )
            .addJsonAttribute(
                DocumentAttributeType.ComplexType,
                "nationalities",
                "Nationality",
                "List of Alpha-2 country codes as specified in ISO 3166-1, representing the nationality of the PID User.",
                Icon.LANGUAGE,
                buildJsonArray {
                    add(JsonPrimitive(SampleData.NATIONALITY))
                }
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "issuance_date",
                "Date of issue",
                "Date (and possibly time) when the PID was issued.",
                Icon.DATE_RANGE,
                JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                "Date of expiry",
                "Date (and possibly time) when the PID will expire.",
                Icon.CALENDAR_CLOCK,
                JsonPrimitive(SampleData.EXPIRY_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "issuing_authority",
                "Issuing authority",
                "Name of the administrative authority that has issued this PID instance, or the " +
                        "ISO 3166 Alpha-2 country code of the respective Member State if there is" +
                        "no separate authority authorized to issue PIDs.",
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_AUTHORITY_EU_PID)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "document_number",
                "Document number",
                "A number for the PID, assigned by the PID Provider.",
                Icon.NUMBERS,
                JsonPrimitive(SampleData.DOCUMENT_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "administrative_number",
                "Administrative number",
                "A number assigned by the PID Provider for audit control or other purposes.",
                Icon.NUMBERS,
                JsonPrimitive(SampleData.ADMINISTRATIVE_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "issuing_jurisdiction",
                "Issuing jurisdiction",
                "Country subdivision code of the jurisdiction that issued the PID, as defined in " +
                        "ISO 3166-2:2020, Clause 8. The first part of the code SHALL be the same " +
                        "as the value for issuing_country.",
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_JURISDICTION)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "issuing_country",
                "Issuing country",
                "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s " +
                        "country or territory",
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_COUNTRY)
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = "Age over 18",
                jsonClaims = listOf("18")
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = "Mandatory data elements",
                jsonClaims = listOf(
                    "family_name",
                    "given_name",
                    "birthdate",
                    "18",
                    "issuance_date",
                    "expiry_date",
                    "issuing_authority",
                    "issuing_country"
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = "All data elements",
                jsonClaims = listOf()
            )
            .build()
    }
}