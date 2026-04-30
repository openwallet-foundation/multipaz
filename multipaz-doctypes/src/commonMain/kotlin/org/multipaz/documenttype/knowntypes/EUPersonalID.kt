/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.DocumentAttributeSensitivity

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
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_EU_PERSONAL_ID))
            .addMdocDocumentType(EUPID_DOCTYPE)
            .addJsonDocumentType(type = EUPID_VCT, keyBound = true)
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.FAMILY_NAME.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_GIVEN_NAMES),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_DATE_OF_BIRTH),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_in_years",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_AGE_IN_YEARS),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_AGE_IN_YEARS),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_IN_YEARS.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_IN_YEARS)
            )
            .addAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_birth_year",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_YEAR_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_YEAR_OF_BIRTH),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_BIRTH_YEAR.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_BIRTH_YEAR)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "age_equal_or_over",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_OLDER_THAN_AGE_ATTESTATIONS),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_OLDER_THAN_AGE_ATTESTATIONS),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_OLDER_THAN_18),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_OLDER_THAN_18),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_18.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_OVER_18)
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                mdocIdentifier = "age_over_21",
                jsonIdentifier = "age_equal_or_over.21",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_OLDER_THAN_21),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_OLDER_THAN_21),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.AGE_OVER_21.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.AGE_OVER_21)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "family_name_birth",
                jsonIdentifier = "birth_family_name",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_FAMILY_NAME_AT_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_FAMILY_NAME_AT_BIRTH),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_FIRST_NAME_AT_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_FIRST_NAME_AT_BIRTH),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PERSON,
                sampleValueMdoc = SampleData.GIVEN_NAME_BIRTH.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.GIVEN_NAME_BIRTH)
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_place",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_PLACE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_PLACE_OF_BIRTH),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.BIRTH_PLACE.toDataItem(),
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "place_of_birth",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_PLACE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_PLACE_OF_BIRTH),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_COUNTRY_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_COUNTRY_OF_BIRTH),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_STATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_STATE_OF_BIRTH),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_CITY_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_CITY_OF_BIRTH),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.BIRTH_CITY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.BIRTH_CITY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "address",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_ADDRESS),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_ADDRESS),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_ADDRESS),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_ADDRESS),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_COUNTRY),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_STATE),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_STATE),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_CITY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_CITY),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_POSTAL_CODE),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_STREET),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_STREET),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_RESIDENT_HOUSE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_RESIDENT_HOUSE_NUMBER),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.RESIDENT_HOUSE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.RESIDENT_HOUSE_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "sex",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_SEX),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_SEX),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_NATIONALITY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_NATIONALITY),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_DATE_OF_ISSUE),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_DATE_OF_ISSUE),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.DATE_RANGE,
                sampleValueMdoc = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.Date,
                mdocIdentifier = "expiry_date",
                jsonIdentifier = "date_of_expiry",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_DATE_OF_EXPIRY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_DATE_OF_EXPIRY),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValueMdoc = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate(),
                sampleValueJson = JsonPrimitive(SampleData.EXPIRY_DATE)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_authority",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_ISSUING_AUTHORITY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_ISSUING_AUTHORITY),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_AUTHORITY_EU_PID.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_AUTHORITY_EU_PID)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "document_number",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_DOCUMENT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_DOCUMENT_NUMBER),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.DOCUMENT_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.DOCUMENT_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "personal_administrative_number",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_PERSONAL_ADMINISTRATIVE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_PERSONAL_ADMINISTRATIVE_NUMBER),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.ADMINISTRATIVE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ADMINISTRATIVE_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_jurisdiction",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_ISSUING_JURISDICTION),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_ISSUING_JURISDICTION),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_JURISDICTION.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_JURISDICTION)
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "issuing_country",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_ISSUING_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_ISSUING_COUNTRY),
                mandatory = true,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValueMdoc = SampleData.ISSUING_COUNTRY.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.ISSUING_COUNTRY)
            )
            .addAttribute(
                type = DocumentAttributeType.Picture,
                mdocIdentifier = "portrait",
                jsonIdentifier = "picture",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_PHOTO_OF_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_PHOTO_OF_HOLDER),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE,
                icon = Icon.ACCOUNT_BOX,
                sampleValueMdoc = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.PORTRAIT_BASE64URL)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                mdocIdentifier = "email_address",
                jsonIdentifier = "email",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_EMAIL_ADDRESS_OF_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_EMAIL_ADDRESS_OF_HOLDER),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_ATTRIBUTE_MOBILE_PHONE_OF_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_DESCRIPTION_MOBILE_PHONE_OF_HOLDER),
                mandatory = false,
                mdocNamespace = EUPID_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.MOBILE_PHONE_NUMBER.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.MOBILE_PHONE_NUMBER)
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_REQUEST_AGE_OVER_18),
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                jsonClaims = listOf("age_equal_or_over.18")
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_REQUEST_AGE_OVER_18_ZKP),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_REQUEST_AGE_OVER_18_AND_PORTRAIT),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_REQUEST_MANDATORY_DATA_ELEMENTS),
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
                displayName = getLocalizedString(GeneratedStringKeys.EU_PERSONAL_ID_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf()
                ),
                jsonClaims = listOf()
            )
            .addSampleRequest(
                id = "withTransaction",
                displayName = "With Transaction Data",
                mdocDataElements = mapOf(
                    EUPID_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                    )
                ),
                jsonClaims = listOf(
                    "family_name",
                    "given_name",
                    "birthdate",
                ),
            )
            .build()
    }
}
