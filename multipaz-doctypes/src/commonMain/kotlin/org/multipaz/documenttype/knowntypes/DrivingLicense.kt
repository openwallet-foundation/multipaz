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

import kotlinx.datetime.LocalDate
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.IntegerOption
import org.multipaz.documenttype.StringOption
import org.multipaz.util.fromBase64Url
import org.multipaz.documenttype.DocumentAttributeSensitivity

/**
 * Object containing the metadata of the Driving License
 * Document Type.
 */
object DrivingLicense {
    const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
    const val MDL_NAMESPACE = "org.iso.18013.5.1"
    const val AAMVA_NAMESPACE = "org.iso.18013.5.1.aamva"

    /**
     * Build the Driving License Document Type. This is ISO mdoc only.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_DRIVING_LICENSE))
            .addMdocDocumentType(MDL_DOCTYPE)
            /*
             * First the attributes that the mDL and VC Credential Type have in common
             */
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.FAMILY_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_GIVEN_NAMES),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "birth_date",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_DATE_OF_BIRTH),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.TODAY,
                sampleValue = LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issue_date",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_DATE_OF_ISSUE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_DATE_OF_ISSUE),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.DATE_RANGE,
                sampleValue = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "expiry_date",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_DATE_OF_EXPIRY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_DATE_OF_EXPIRY),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "issuing_country",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ISSUING_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ISSUING_COUNTRY),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = SampleData.ISSUING_COUNTRY.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_authority",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ISSUING_AUTHORITY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ISSUING_AUTHORITY),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = SampleData.ISSUING_AUTHORITY_MDL.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "document_number",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_LICENSE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_LICENSE_NUMBER),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = SampleData.DOCUMENT_NUMBER.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "portrait",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_PHOTO_OF_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_PHOTO_OF_HOLDER),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE,
                icon = Icon.ACCOUNT_BOX,
                sampleValue = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "driving_privileges",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_DRIVING_PRIVILEGES),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_DRIVING_PRIVILEGES),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.DIRECTIONS_CAR,
                sampleValue = buildCborArray {
                    addCborMap {
                        put("vehicle_category_code", "A")
                        put("issue_date", Tagged(Tagged.FULL_DATE_STRING, Tstr("2018-08-09")))
                        put("expiry_date", Tagged(Tagged.FULL_DATE_STRING, Tstr("2028-09-01")))
                    }
                    addCborMap {
                        put("vehicle_category_code", "B")
                        put("issue_date", Tagged(Tagged.FULL_DATE_STRING, Tstr("2017-02-23")))
                        put("expiry_date", Tagged(Tagged.FULL_DATE_STRING, Tstr("2028-09-01")))
                    }
                }
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.DISTINGUISHING_SIGN_ISO_IEC_18013_1_ANNEX_F),
                identifier = "un_distinguishing_sign",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_UN_DISTINGUISHING_SIGN),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_UN_DISTINGUISHING_SIGN),
                mandatory = true,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.LANGUAGE,
                sampleValue = SampleData.UN_DISTINGUISHING_SIGN.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "administrative_number",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ADMINISTRATIVE_NUMBER),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "sex",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_SEX),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_SEX),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = SampleData.SEX_ISO_5218.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "height",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_HEIGHT),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_HEIGHT),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = SampleData.HEIGHT_CM.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "weight",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_WEIGHT),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_WEIGHT),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = SampleData.WEIGHT_KG.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("black", "Black"),
                        StringOption("blue", "Blue"),
                        StringOption("brown", "Brown"),
                        StringOption("dichromatic", "Dichromatic"),
                        StringOption("grey", "Grey"),
                        StringOption("green", "Green"),
                        StringOption("hazel", "Hazel"),
                        StringOption("maroon", "Maroon"),
                        StringOption("pink", "Pink"),
                        StringOption("unknown", "Unknown")
                    )
                ),
                identifier = "eye_colour",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_EYE_COLOR),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_EYE_COLOR),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "blue".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("bald", "Bald"),
                        StringOption("black", "Black"),
                        StringOption("blond", "Blond"),
                        StringOption("brown", "Brown"),
                        StringOption("grey", "Grey"),
                        StringOption("red", "Red"),
                        StringOption("auburn", "Auburn"),
                        StringOption("sandy", "Sandy"),
                        StringOption("white", "White"),
                        StringOption("unknown", "Unknown"),
                    )
                ),
                identifier = "hair_colour",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_HAIR_COLOR),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_HAIR_COLOR),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "blond".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_place",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_PLACE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_PLACE_OF_BIRTH),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.BIRTH_PLACE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_address",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_ADDRESS),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_ADDRESS),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "portrait_capture_date",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_PORTRAIT_IMAGE_TIMESTAMP),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_PORTRAIT_IMAGE_TIMESTAMP),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.TODAY,
                sampleValue = LocalDate.parse(SampleData.PORTRAIT_CAPTURE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_in_years",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_AGE_IN_YEARS),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_AGE_IN_YEARS),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_IN_YEARS.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_birth_year",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_YEAR_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_YEAR_OF_BIRTH),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_BIRTH_YEAR.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_13",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_13),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_13),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_13.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_16",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_16),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_16),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_16.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_18),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_18),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_18.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_21",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_21),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_21),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_21.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_25",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_25),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_25),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_25.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_60",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_60),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_60),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_60.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_62",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_62),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_62),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_62.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_65",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_65),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_65),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_65.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "age_over_68",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_OLDER_THAN_68),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_OLDER_THAN_68),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = SampleData.AGE_OVER_68.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_jurisdiction",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ISSUING_JURISDICTION),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ISSUING_JURISDICTION),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = SampleData.ISSUING_JURISDICTION.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "nationality",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_NATIONALITY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_NATIONALITY),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.LANGUAGE,
                sampleValue = SampleData.NATIONALITY.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_city",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_CITY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_CITY),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_CITY.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_state",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_STATE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_STATE),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_STATE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_postal_code",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_POSTAL_CODE),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "resident_country",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_COUNTRY),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_COUNTRY.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name_national_character",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_FAMILY_NAME_NATIONAL_CHARACTERS),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_FAMILY_NAME_NATIONAL_CHARACTERS),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.FAMILY_NAME_NATIONAL_CHARACTER.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name_national_character",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_GIVEN_NAME_NATIONAL_CHARACTERS),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_GIVEN_NAME_NATIONAL_CHARACTERS),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.GIVEN_NAMES_NATIONAL_CHARACTER.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "signature_usual_mark",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_SIGNATURE_USUAL_MARK),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_SIGNATURE_USUAL_MARK),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.SIGNATURE,
                sampleValue = SampleData.SIGNATURE_OR_USUAL_MARK_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "domestic_driving_privileges",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_DOMESTIC_DRIVING_PRIVILEGES),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_DOMESTIC_DRIVING_PRIVILEGES),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.DIRECTIONS_CAR,
                sampleValue = buildCborArray {}
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.AAMVA_NAME_SUFFIX),
                identifier = "name_suffix",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_NAME_SUFFIX),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_NAME_SUFFIX),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "Jr III".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Donor")
                    )
                ),
                identifier = "organ_donor",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ORGAN_DONOR),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ORGAN_DONOR),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = 1.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Veteran")
                    )
                ),
                identifier = "veteran",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_VETERAN),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_VETERAN),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.MILITARY_TECH,
                sampleValue = 1.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("T", "Truncated"),
                        StringOption("N", "Not truncated"),
                        StringOption("U", "Unknown whether truncated"),
                    )
                ),
                identifier = "family_name_truncation",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_FAMILY_NAME_TRUNCATION),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_FAMILY_NAME_TRUNCATION),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "N".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("T", "Truncated"),
                        StringOption("N", "Not truncated"),
                        StringOption("U", "Unknown whether truncated"),
                    )
                ),
                identifier = "given_name_truncation",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_GIVEN_NAME_TRUNCATION),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_GIVEN_NAME_TRUNCATION),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "N".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "aka_family_name",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ALIAS_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ALIAS_FAMILY_NAME),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "Musstermensch".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "aka_given_name",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ALIAS_GIVEN_NAME),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ALIAS_GIVEN_NAME),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "Erica".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(Options.AAMVA_NAME_SUFFIX),
                identifier = "aka_suffix",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_ALIAS_SUFFIX),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_ALIAS_SUFFIX),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "Ica".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(0, "Up to 31 kg (up to 70 lbs.)"),
                        IntegerOption(1, "32 – 45 kg (71 – 100 lbs.)"),
                        IntegerOption(2, "46 - 59 kg (101 – 130 lbs.)"),
                        IntegerOption(3, "60 - 70 kg (131 – 160 lbs.)"),
                        IntegerOption(4, "71 - 86 kg (161 – 190 lbs.)"),
                        IntegerOption(5, "87 - 100 kg (191 – 220 lbs.)"),
                        IntegerOption(6, "101 - 113 kg (221 – 250 lbs.)"),
                        IntegerOption(7, "114 - 127 kg (251 – 280 lbs.)"),
                        IntegerOption(8, "128 – 145 kg (281 – 320 lbs.)"),
                        IntegerOption(9, "146+ kg (321+ lbs.)"),
                    )
                ),
                identifier = "weight_range",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_WEIGHT_RANGE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_WEIGHT_RANGE),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = 3.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("AI", "Alaskan or American Indian"),
                        StringOption("AP", "Asian or Pacific Islander"),
                        StringOption("BK", "Black"),
                        StringOption("H", "Hispanic Origin"),
                        StringOption("O", "Non-hispanic"),
                        StringOption("U", "Unknown"),
                        StringOption("W", "White")
                    )
                ),
                identifier = "race_ethnicity",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RACE_ETHNICITY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RACE_ETHNICITY),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = "W".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("F", "Fully compliant"),
                        StringOption("N", "Non-compliant"),
                    )
                ),
                identifier = "DHS_compliance",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_COMPLIANCE_TYPE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_COMPLIANCE_TYPE),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.STARS,
                sampleValue = "F".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Temporary lawful status")
                    )
                ),
                identifier = "DHS_temporary_lawful_status",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_LIMITED_DURATION_DOCUMENT_INDICATOR),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_LIMITED_DURATION_DOCUMENT_INDICATOR),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.STARS,
                sampleValue = 1.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Driver's license"),
                        IntegerOption(2, "Identification card")
                    )
                ),
                identifier = "EDL_credential",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_EDL_INDICATOR),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_EDL_INDICATOR),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.DIRECTIONS_CAR,
                sampleValue = 1.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "resident_county",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_RESIDENT_COUNTY),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_RESIDENT_COUNTY),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "037".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "hazmat_endorsement_expiration_date",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_HAZMAT_ENDORSEMENT_EXPIRATION_DATE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_HAZMAT_ENDORSEMENT_EXPIRATION_DATE),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "sex",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_SEX),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_SEX),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                icon = Icon.EMERGENCY,
                sampleValue = SampleData.SEX_ISO_5218.toDataItem()
            )
            /*
             * Then the attributes that exist only in the mDL Credential Type and not in the VC Credential Type
             */
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "biometric_template_face",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_BIOMETRIC_TEMPLATE_FACE),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_BIOMETRIC_TEMPLATE_FACE),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.FACE,
                sampleValue = Simple.NULL
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "biometric_template_finger",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_BIOMETRIC_TEMPLATE_FINGERPRINT),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_BIOMETRIC_TEMPLATE_FINGERPRINT),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.FINGERPRINT,
                sampleValue = Simple.NULL
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "biometric_template_signature_sign",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_BIOMETRIC_TEMPLATE_SIGNATURE_SIGN),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_BIOMETRIC_TEMPLATE_SIGNATURE_SIGN),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.SIGNATURE,
                sampleValue = Simple.NULL
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "biometric_template_iris",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_BIOMETRIC_TEMPLATE_IRIS),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_BIOMETRIC_TEMPLATE_IRIS),
                mandatory = false,
                mdocNamespace = MDL_NAMESPACE,
                icon = Icon.EYE_TRACKING,
                sampleValue = Simple.NULL
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "audit_information",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_AUDIT_INFORMATION),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_AUDIT_INFORMATION),
                mandatory = false,
                mdocNamespace = AAMVA_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.STARS,
                sampleValue = "".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "aamva_version",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_AAMVA_VERSION_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_AAMVA_VERSION_NUMBER),
                mandatory = true,
                mdocNamespace = AAMVA_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.NUMBERS,
                sampleValue = 1.toDataItem()
            )
            .addSampleRequest(
                id = "us-transportation",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_US_TRANSPORTATION),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "sex" to false,
                        "portrait" to false,
                        "given_name" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                        "family_name" to false,
                        "document_number" to false,
                        "issuing_authority" to false
                    ),
                    AAMVA_NAMESPACE to mapOf(
                        "DHS_compliance" to false,
                        "EDL_credential" to false
                    ),
                )
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_18),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_21),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_18_ZKP),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_21_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_21_ZKP),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_18_AND_PORTRAIT),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21_and_portrait",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_AGE_OVER_21_AND_PORTRAIT),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_MANDATORY_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                        "issuing_country" to false,
                        "issuing_authority" to false,
                        "document_number" to false,
                        "portrait" to false,
                        "driving_privileges" to false,
                        "un_distinguishing_sign" to false,
                    )
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(),
                    AAMVA_NAMESPACE to mapOf()
                )
            )
            .addSampleRequest(
                id = "name-and-address-partially-stored",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_NAME_AND_ADDRESS_PARTIALLY_STORED),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "family_name" to true,
                        "given_name" to true,
                        "issuing_authority" to false,
                        "portrait" to false,
                        "resident_address" to true,
                        "resident_city" to true,
                        "resident_state" to true,
                        "resident_postal_code" to true,
                        "resident_country" to true,
                    ),
                    AAMVA_NAMESPACE to mapOf(
                        "resident_county" to true,
                    )
                )
            )
            .addSampleRequest(
                id = "name-and-address-all-stored",
                displayName = getLocalizedString(GeneratedStringKeys.DRIVING_LICENSE_REQUEST_NAME_AND_ADDRESS_ALL_STORED),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "family_name" to true,
                        "given_name" to true,
                        "issuing_authority" to true,
                        "portrait" to true,
                        "resident_address" to true,
                        "resident_city" to true,
                        "resident_state" to true,
                        "resident_postal_code" to true,
                        "resident_country" to true,
                    ),
                    AAMVA_NAMESPACE to mapOf(
                        "resident_county" to true,
                    )
                )
            )
            .build()
    }
}
