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

import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.IntegerOption
import org.multipaz.documenttype.StringOption
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.LocalDate
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap

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
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Driving license")
            .addMdocDocumentType(MDL_DOCTYPE)
            /*
             * First the attributes that the mDL and VC Credential Type have in common
             */
            .addMdocAttribute(
                DocumentAttributeType.String,
                "family_name",
                "Family name",
                "Last name, surname, or primary identifier, of the mDL holder.",
                true,
                MDL_NAMESPACE,
                Icon.PERSON,
                SampleData.FAMILY_NAME.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "given_name",
                "Given names",
                "First name(s), other name(s), or secondary identifier, of the mDL holder",
                true,
                MDL_NAMESPACE,
                Icon.PERSON,
                SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                "Date of birth",
                "Day, month and year on which the mDL holder was born. If unknown, approximate date of birth",
                true,
                MDL_NAMESPACE,
                Icon.TODAY,
                LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                "Date of issue",
                "Date when mDL was issued",
                true,
                MDL_NAMESPACE,
                Icon.DATE_RANGE,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                "Date of expiry",
                "Date when mDL expires",
                true,
                MDL_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "issuing_country",
                "Issuing country",
                "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s country or territory",
                true,
                MDL_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_COUNTRY.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuing_authority",
                "Issuing authority",
                "Issuing authority name.",
                true,
                MDL_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_AUTHORITY_MDL.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "document_number",
                "License number",
                "The number assigned or calculated by the issuing authority.",
                true,
                MDL_NAMESPACE,
                Icon.NUMBERS,
                SampleData.DOCUMENT_NUMBER.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "portrait",
                "Photo of holder",
                "A reproduction of the mDL holder’s portrait.",
                true,
                MDL_NAMESPACE,
                Icon.ACCOUNT_BOX,
                SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "driving_privileges",
                "Driving privileges",
                "Driving privileges of the mDL holder",
                true,
                MDL_NAMESPACE,
                Icon.DIRECTIONS_CAR,
                buildCborArray {
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
                DocumentAttributeType.StringOptions(Options.DISTINGUISHING_SIGN_ISO_IEC_18013_1_ANNEX_F),
                "un_distinguishing_sign",
                "UN distinguishing sign",
                "Distinguishing sign of the issuing country",
                true,
                MDL_NAMESPACE,
                Icon.LANGUAGE,
                SampleData.UN_DISTINGUISHING_SIGN.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "administrative_number",
                "Administrative number",
                "An audit control number assigned by the issuing authority",
                false,
                MDL_NAMESPACE,
                Icon.NUMBERS,
                SampleData.ADMINISTRATIVE_NUMBER.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "sex",
                "Sex",
                "mDL holder’s sex",
                false,
                MDL_NAMESPACE,
                Icon.EMERGENCY,
                SampleData.SEX_ISO_5218.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "height",
                "Height",
                "mDL holder’s height in centimetres",
                false,
                MDL_NAMESPACE,
                Icon.EMERGENCY,
                SampleData.HEIGHT_CM.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "weight",
                "Weight",
                "mDL holder’s weight in kilograms",
                false,
                MDL_NAMESPACE,
                Icon.EMERGENCY,
                SampleData.WEIGHT_KG.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
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
                "eye_colour",
                "Eye color",
                "mDL holder’s eye color",
                false,
                MDL_NAMESPACE,
                Icon.PERSON,
                "blue".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
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
                "hair_colour",
                "Hair color",
                "mDL holder’s hair color",
                false,
                MDL_NAMESPACE,
                Icon.PERSON,
                "blond".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "birth_place",
                "Place of birth",
                "Country and municipality or state/province where the mDL holder was born",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.BIRTH_PLACE.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_address",
                "Resident address",
                "The place where the mDL holder resides and/or may be contacted (street/house number, municipality etc.)",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "portrait_capture_date",
                "Portrait image timestamp",
                "Date when portrait was taken",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                LocalDate.parse(SampleData.PORTRAIT_CAPTURE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "age_in_years",
                "Age in years",
                "The age of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_IN_YEARS.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "age_birth_year",
                "Year of birth",
                "The year when the mDL holder was born",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_BIRTH_YEAR.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_13",
                "Older than 13 years",
                "Indication whether the mDL holder is as old or older than 13",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_13.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_16",
                "Older than 16 years",
                "Indication whether the mDL holder is as old or older than 16",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_16.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_18",
                "Older than 18 years",
                "Indication whether the mDL holder is as old or older than 18",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_18.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_21",
                "Older than 21 years",
                "Indication whether the mDL holder is as old or older than 21",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_21.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_25",
                "Older than 25 years",
                "Indication whether the mDL holder is as old or older than 25",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_25.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_60",
                "Older than 60 years",
                "Indication whether the mDL holder is as old or older than 60",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_60.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_62",
                "Older than 62 years",
                "Indication whether the mDL holder is as old or older than 62",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_62.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_65",
                "Older than 65 years",
                "Indication whether the mDL holder is as old or older than 65",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_65.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_68",
                "Older than 68 years",
                "Indication whether the mDL holder is as old or older than 68",
                false,
                MDL_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER_68.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuing_jurisdiction",
                "Issuing jurisdiction",
                "Country subdivision code of the jurisdiction that issued the mDL",
                false,
                MDL_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                SampleData.ISSUING_JURISDICTION.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "nationality",
                "Nationality",
                "Nationality of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.LANGUAGE,
                SampleData.NATIONALITY.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_city",
                "Resident city",
                "The city where the mDL holder lives",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_CITY.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_state",
                "Resident state",
                "The state/province/district where the mDL holder lives",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_STATE.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_postal_code",
                "Resident postal code",
                "The postal code of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "resident_country",
                "Resident country",
                "The country where the mDL holder lives",
                false,
                MDL_NAMESPACE,
                Icon.PLACE,
                SampleData.RESIDENT_COUNTRY.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "family_name_national_character",
                "Family name national characters",
                "The family name of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.PERSON,
                SampleData.FAMILY_NAME_NATIONAL_CHARACTER.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "given_name_national_character",
                "Given name national characters",
                "The given name of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.PERSON,
                SampleData.GIVEN_NAMES_NATIONAL_CHARACTER.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "signature_usual_mark",
                "Signature / usual mark",
                "Image of the signature or usual mark of the mDL holder,",
                false,
                MDL_NAMESPACE,
                Icon.SIGNATURE,
                SampleData.SIGNATURE_OR_USUAL_MARK_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "domestic_driving_privileges",
                "Domestic driving privileges",
                "Vehicle types the license holder is authorized to operate",
                true,
                AAMVA_NAMESPACE,
                Icon.DIRECTIONS_CAR,
                buildCborArray {}
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(Options.AAMVA_NAME_SUFFIX),
                "name_suffix",
                "Name suffix",
                "Name suffix of the individual that has been issued the driver license or identification document.",
                false,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "Jr III".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Donor")
                    )
                ),
                "organ_donor",
                "Organ donor",
                "An indicator that denotes whether the credential holder is an organ donor.",
                false,
                AAMVA_NAMESPACE,
                Icon.EMERGENCY,
                1.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Veteran")
                    )
                ),
                "veteran",
                "Veteran",
                "An indicator that denotes whether the credential holder is a veteran.",
                false,
                AAMVA_NAMESPACE,
                Icon.MILITARY_TECH,
                1.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("T", "Truncated"),
                        StringOption("N", "Not truncated"),
                        StringOption("U", "Unknown whether truncated"),
                    )
                ),
                "family_name_truncation",
                "Family name truncation",
                "A code that indicates whether the field has been truncated",
                true,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "N".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("T", "Truncated"),
                        StringOption("N", "Not truncated"),
                        StringOption("U", "Unknown whether truncated"),
                    )
                ),
                "given_name_truncation",
                "Given name truncation",
                "A code that indicates whether either the first name or the middle name(s) have been truncated",
                true,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "N".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "aka_family_name",
                "Alias / AKA family name",
                "Other family name by which credential holder is known.",
                false,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "Musstermensch".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "aka_given_name",
                "Alias / AKA given name",
                "Other given name by which credential holder is known.",
                false,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "Erica".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(Options.AAMVA_NAME_SUFFIX),
                "aka_suffix",
                "Alias / AKA suffix name",
                "Other suffix by which credential holder is known.",
                false,
                AAMVA_NAMESPACE,
                Icon.PERSON,
                "Ica".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(
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
                "weight_range",
                "Weight range",
                "Indicates the approximate weight range of the cardholder",
                false,
                AAMVA_NAMESPACE,
                Icon.EMERGENCY,
                3.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
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
                "race_ethnicity",
                "Race / ethnicity",
                "Codes for race or ethnicity of the cardholder",
                false,
                AAMVA_NAMESPACE,
                Icon.EMERGENCY,
                "W".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption(null, "(not set)"),
                        StringOption("F", "Fully compliant"),
                        StringOption("N", "Non-compliant"),
                    )
                ),
                "DHS_compliance",
                "Compliance type",
                "DHS required field that indicates compliance",
                false,
                AAMVA_NAMESPACE,
                Icon.STARS,
                "F".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Temporary lawful status")
                    )
                ),
                "DHS_temporary_lawful_status",
                "Limited duration document indicator",
                "DHS required field that denotes whether the credential holder has temporary lawful status. 1: Temporary lawful status",
                false,
                AAMVA_NAMESPACE,
                Icon.STARS,
                1.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(
                    listOf(
                        IntegerOption(null, "(not set)"),
                        IntegerOption(1, "Driver's license"),
                        IntegerOption(2, "Identification card")
                    )
                ),
                "EDL_credential",
                "EDL indicator",
                "Present if the credential is an EDL",
                false,
                AAMVA_NAMESPACE,
                Icon.DIRECTIONS_CAR,
                1.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_county",
                "Resident county",
                "The 3-digit county code of the county where the mDL holder lives",
                false,
                AAMVA_NAMESPACE,
                Icon.PLACE,
                "037".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "hazmat_endorsement_expiration_date",
                "HAZMAT endorsement expiration date",
                "Date on which the hazardous material endorsement granted by the document is no longer valid.",
                true,
                AAMVA_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "sex",
                "Sex",
                "mDL holder’s sex",
                true,
                AAMVA_NAMESPACE,
                Icon.EMERGENCY,
                SampleData.SEX_ISO_5218.toDataItem()
            )
            /*
             * Then the attributes that exist only in the mDL Credential Type and not in the VC Credential Type
             */
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "biometric_template_face",
                "Biometric template face",
                "Facial biometric information of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.FACE,
                Simple.NULL
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "biometric_template_finger",
                "Biometric template fingerprint",
                "Fingerprint of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.FINGERPRINT,
                Simple.NULL
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "biometric_template_signature_sign",
                "Biometric template signature/sign",
                "Signature/sign of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.SIGNATURE,
                Simple.NULL
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "biometric_template_iris",
                "Biometric template iris",
                "Iris of the mDL holder",
                false,
                MDL_NAMESPACE,
                Icon.EYE_TRACKING,
                Simple.NULL
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "audit_information",
                "Audit information",
                "A string of letters and/or numbers that identifies when, where, and by whom the credential was initially provisioned.",
                false,
                AAMVA_NAMESPACE,
                Icon.STARS,
                "".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "aamva_version",
                "AAMVA version number",
                "A number identifying the version of the AAMVA mDL data element set",
                true,
                AAMVA_NAMESPACE,
                Icon.NUMBERS,
                1.toDataItem()
            )
            .addSampleRequest(
                id = "us-transportation",
                displayName = "US transportation",
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
                displayName ="Age over 18",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21",
                displayName ="Age over 21",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName ="Age over 18 (ZKP)",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_21_zkp",
                displayName ="Age over 21 (ZKP)",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName ="Age over 18 + portrait",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21_and_portrait",
                displayName ="Age over 21 + portrait",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = "Mandatory data elements",
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
                displayName ="All data elements",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(),
                    AAMVA_NAMESPACE to mapOf()
                )
            )
            .addSampleRequest(
                id = "name-and-address-partially-stored",
                displayName = "Name and address (partially stored)",
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
                displayName = "Name and address (all stored)",
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