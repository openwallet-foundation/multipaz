/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.cbor.buildCborArray

/**
 * Object containing the metadata of the EU Personal ID Document Type.
 *
 * Source: https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework/blob/main/docs/annexes/annex-06-pid-rulebook.md
 */
object EUPersonalID {
    const val EUPID_DOCTYPE = "eu.europa.ec.eudi.pid.1"
    const val EUPID_NAMESPACE = "eu.europa.ec.eudi.pid.1"
    const val EUPID_VCT = "urn:eudi:pid:1"

    /**
     * Build the EU Personal ID Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("EU Personal ID")
            .addMdocDocumentType(EUPID_DOCTYPE)
            .addJsonDocumentType(type = EUPID_VCT, keyBound = true)
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = "Family Name",
                description = "Current last name(s), surname(s), or primary identifier of the PID holder",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.FAMILY_NAME.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = "Given Names",
                description = "Current first name(s), other name(s), or secondary identifier of the PID holder",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.GIVEN_NAME.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                mdocIdentifier = "birth_date",
                jsonIdentifier = "birthdate",
                displayName = "Date of Birth",
                description = "Day, month, and year on which the PID holder was born. If unknown, approximate date of birth.",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_in_years",
                displayName = "Age in Years",
                description = "The age of the PID holder in years",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_IN_YEARS.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_IN_YEARS)
            )
            .addAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_birth_year",
                displayName = "Year of Birth",
                description = "The year when the PID holder was born",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_BIRTH_YEAR.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_BIRTH_YEAR)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "age_equal_or_over",
                displayName = "Older Than Age Attestations",
                description = "Older Than Age Attestations",
                icon = Icon.TODAY,
                sampleValue = buildJsonObject {
                    put("18", JsonPrimitive(SampleData.AGE_OVER_18))
                    put("21", JsonPrimitive(SampleData.AGE_OVER_21))
                }
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                mdocIdentifier = "age_over_18",
                jsonIdentifier = "age_equal_or_over.18",
                displayName = "Older Than 18",
                description = "Age over 18?",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_18.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_OVER_18)
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                mdocIdentifier = "age_over_21",
                jsonIdentifier = "age_equal_or_over.21",
                displayName = "Older Than 21",
                description = "Age over 21?",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_21.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_OVER_21)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "family_name_birth",
                jsonIdentifier = "birth_family_name",
                displayName = "Family Name at Birth",
                description = "Last name(s), surname(s), or primary identifier of the PID holder at birth",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.FAMILY_NAME_BIRTH.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.FAMILY_NAME_BIRTH)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "given_name_birth",
                jsonIdentifier = "birth_given_name",
                displayName = "First Name at Birth",
                description = "First name(s), other name(s), or secondary identifier of the PID holder at birth",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.GIVEN_NAME_BIRTH.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.GIVEN_NAME_BIRTH)
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_place",
                displayName = "Place of Birth",
                description = "Country and municipality or state/province where the PID holder was born",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.BIRTH_PLACE.toDataItem(),
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "place_of_birth",
                displayName = "Place of Birth",
                description = "Country and municipality or state/province where the PID holder was born",
                icon = Icon.PLACE,
                sampleValue = buildJsonObject {
                    put("country", JsonPrimitive(SampleData.BIRTH_COUNTRY))
                    put("region", JsonPrimitive(SampleData.BIRTH_STATE))
                    put("locality", JsonPrimitive(SampleData.BIRTH_CITY))
                }
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                mdocIdentifier = "birth_country",
                jsonIdentifier = "place_of_birth.country",
                displayName = "Country of Birth",
                description = "The country where the PID User was born, as an Alpha-2 country code as specified in ISO 3166-1",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.BIRTH_COUNTRY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_COUNTRY)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "birth_state",
                jsonIdentifier = "place_of_birth.region",
                displayName = "State of Birth",
                description = "The state, province, district, or local area where the PID User was born",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.BIRTH_STATE.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_STATE)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "birth_city",
                jsonIdentifier = "place_of_birth.locality",
                displayName = "City of Birth",
                description = "The municipality, city, town, or village where the PID User was born",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.BIRTH_CITY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_CITY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "address",
                displayName = "Address",
                description = "Address",
                icon = Icon.PLACE,
                sampleValue = buildJsonObject {
                    put("formatted", JsonPrimitive(SampleData.RESIDENT_ADDRESS))
                    put("country", JsonPrimitive(SampleData.RESIDENT_COUNTRY))
                    put("region", JsonPrimitive(SampleData.RESIDENT_STATE))
                    put("locality", JsonPrimitive(SampleData.RESIDENT_CITY))
                    put("postal_code", JsonPrimitive(SampleData.RESIDENT_POSTAL_CODE))
                    put("street_address", JsonPrimitive(SampleData.RESIDENT_STREET))
                    put("house_number", JsonPrimitive(SampleData.RESIDENT_HOUSE_NUMBER))
                }
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_address",
                jsonIdentifier = "address.formatted",
                displayName = "Resident Address",
                description = "The full address of the place where the PID holder currently resides and/or may be contacted (street/house number, municipality etc.)",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_ADDRESS.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_ADDRESS)
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                mdocIdentifier = "resident_country",
                jsonIdentifier = "address.country",
                displayName = "Resident Country",
                description = "The country where the PID User currently resides, as an Alpha-2 country code as specified in ISO 3166-1",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_COUNTRY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_COUNTRY)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_state",
                jsonIdentifier = "address.region",
                displayName = "Resident State",
                description = "The state, province, district, or local area where the PID User currently resides.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_STATE.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_STATE)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_city",
                jsonIdentifier = "address.locality",
                displayName = "Resident City",
                description = "The city where the PID holder currently resides",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_CITY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_CITY)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_postal_code",
                jsonIdentifier = "address.postal_code",
                displayName = "Resident Postal Code",
                description = "The postal code of the place where the PID holder currently resides",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_POSTAL_CODE.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_POSTAL_CODE)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_street",
                jsonIdentifier = "address.street_address",
                displayName = "Resident Street",
                description = "The name of the street where the PID User currently resides.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_STREET.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_STREET)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "resident_house_number",
                jsonIdentifier = "address.house_number",
                displayName = "Resident House Number",
                description = "The house number where the PID User currently resides, including any affix or suffix",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_HOUSE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_HOUSE_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "sex",
                displayName = "Sex",
                description = "PID holder’s sex",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValueMdoc = SampleData.SEX_ISO_5218.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.SEX_ISO_5218)
            )
            .addAttribute(
                type = DocumentAttributeType.ComplexType,
                mdocIdentifier = "nationality",
                jsonIdentifier = "nationalities",
                displayName = "Nationality",
                description = "Alpha-2 country code as specified in ISO 3166-1, representing the nationality of the PID User.",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.LANGUAGE,
                sampleValueMdoc = buildCborArray {
                    add(SampleData.NATIONALITY)
                    add(SampleData.SECOND_NATIONALITY)
                },
                sampleValueJson = buildJsonArray {
                    add(JsonPrimitive(SampleData.NATIONALITY))
                    add(JsonPrimitive(SampleData.SECOND_NATIONALITY))
                }
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                mdocIdentifier = "issuance_date",
                jsonIdentifier = "date_of_issuance",
                displayName = "Date of Issue",
                description = "Date (and possibly time) when the PID was issued.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.DATE_RANGE,
                sampleValueMdoc = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                mdocIdentifier = "expiry_date",
                jsonIdentifier = "date_of_expiry",
                displayName = "Date of Expiry",
                description = "Date (and possibly time) when the PID will expire.",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.CALENDAR_CLOCK,
                sampleValueMdoc = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.EXPIRY_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_authority",
                displayName = "Issuing Authority",
                description = "Name of the administrative authority that has issued this PID instance, or the " +
                        "ISO 3166 Alpha-2 country code of the respective Member State if there is" +
                        "no separate authority authorized to issue PIDs.",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_AUTHORITY_EU_PID.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_AUTHORITY_EU_PID)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "document_number",
                displayName = "Document Number",
                description = "A number for the PID, assigned by the PID Provider.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.DOCUMENT_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.DOCUMENT_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "personal_administrative_number",
                displayName = "Personal Administrative Number",
                description = "A number assigned by the PID Provider for audit control or other purposes.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.ADMINISTRATIVE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ADMINISTRATIVE_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_jurisdiction",
                displayName = "Issuing Jurisdiction",
                description = "Country subdivision code of the jurisdiction that issued the PID, as defined in " +
                        "ISO 3166-2:2020, Clause 8. The first part of the code SHALL be the same " +
                        "as the value for issuing_country.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_JURISDICTION.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_JURISDICTION)
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "issuing_country",
                displayName = "Issuing Country",
                description = "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s " +
                        "country or territory",
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_COUNTRY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_COUNTRY)
            )
            .addAttribute(
                type = DocumentAttributeType.Picture,
                mdocIdentifier = "portrait",
                jsonIdentifier = "picture",
                displayName = "Photo of Holder",
                description = "A reproduction of the PID holder’s portrait.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.ACCOUNT_BOX,
                sampleValueMdoc = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.PORTRAIT_BASE64URL)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "email_address",
                jsonIdentifier = "email",
                displayName = "Email Address of Holder",
                description = "Electronic mail address of the user to whom the person identification data relates, in conformance with [RFC 5322].",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.EMAIL_ADDRESS.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.EMAIL_ADDRESS)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "mobile_phone_number",
                jsonIdentifier = "phone_number",
                displayName = "Mobile Phone of Holder",
                description = "Mobile telephone number of the User to whom the person identification data relates, starting with the '+' symbol as the international code prefix and the country code, followed by numbers only.",
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.MOBILE_PHONE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.MOBILE_PHONE_NUMBER)
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = "Age Over 18",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                jsonClaims = listOf("age_equal_or_over.18")
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName = "Age Over 18 (ZKP)",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true,
                jsonClaims = listOf("age_equal_or_over.18")
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName = "Age Over 18 + Portrait",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "portrait" to false,
                    )
                ),
                jsonClaims = listOf("age_equal_or_over.18", "picture")
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = "Mandatory Data Elements",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                        "birth_place" to false,
                        "nationality" to false,
                        "expiry_date" to false,
                        "issuing_authority" to false,
                        "issuing_country" to false
                    )
                ),
                jsonClaims = listOf(
                    "family_name",
                    "given_name",
                    "birthdate",
                    "place_of_birth",
                    "nationalities",
                    "date_of_expiry",
                    "issuing_authority",
                    "issuing_country"
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = "All Data Elements",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf()
                ),
                jsonClaims = listOf()
            )
            .build()
    }
}