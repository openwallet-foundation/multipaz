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

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.DocumentAttributeSensitivity

/**
 * Object containing the metadata of the Vaccination
 * Document Type.
 */
object VaccinationDocument {
    const val MICOV_ATT_NAMESPACE = "org.micov.attestation.1"
    const val MICOV_VTR_NAMESPACE = "org.micov.vtr.1"

    /**
     * Build the Vaccination Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_VACCINATION_DOCUMENT))
            .addMdocDocumentType("org.micov.1")
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "1D47_vaccinated",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_YELLOW_FEVER_VACCINATED),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_YELLOW_FEVER_VACCINATED),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "RA01_vaccinated",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_COVID19_VACCINATED),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_COVID19_VACCINATED),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "RA01_test",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_COVID19_TEST),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_COVID19_TEST),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "safeEntry_Leisure",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_SAFE_ENTRY_LEISURE),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_SAFE_ENTRY_LEISURE),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "fac",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_FACIAL_IMAGE),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_FACIAL_IMAGE),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "fni",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_FAMILY_NAME_INITIAL),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_FAMILY_NAME_INITIAL),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "gni",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_GIVEN_NAME_INITIAL),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_GIVEN_NAME_INITIAL),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "by",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_BIRTH_YEAR),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_BIRTH_YEAR),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "bm",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_BIRTH_MONTH),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_BIRTH_MONTH),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Number,
                identifier = "bd",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_BIRTH_DAY),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_BIRTH_DAY),
                mandatory = false,
                mdocNamespace = MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "fn",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "gn",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_GIVEN_NAME),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_GIVEN_NAME),
                mandatory = true,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "dob",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_DATE_OF_BIRTH),
                mandatory = true,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "sex",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_SEX),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_SEX),
                mandatory = false,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "v_RA01_1",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_COVID19_FIRST_VACCINATION),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_COVID19_FIRST_VACCINATION),
                mandatory = false,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "v_RA01_2",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_COVID19_SECOND_VACCINATION),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_COVID19_SECOND_VACCINATION),
                mandatory = false,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "pid_PPN",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_ID_WITH_PASSPORT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_ID_WITH_PASSPORT_NUMBER),
                mandatory = false,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "pid_DL",
                displayName = getLocalizedString(GeneratedStringKeys.VACCINATION_ATTRIBUTE_ID_WITH_DRIVERS_LICENSE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.VACCINATION_DESCRIPTION_ID_WITH_DRIVERS_LICENSE_NUMBER),
                mandatory = false,
                mdocNamespace = MICOV_VTR_NAMESPACE
            )
            .build()
    }
}
