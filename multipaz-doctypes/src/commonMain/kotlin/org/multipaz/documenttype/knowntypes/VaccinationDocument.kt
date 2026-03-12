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
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Vaccination document")
            .addMdocDocumentType("org.micov.1")
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "1D47_vaccinated",
                "Vaccination against yellow fever",
                "Attestation that the holder has been fully vaccinated against Yellow Fever",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "RA01_vaccinated",
                "Vaccination against COVID-19",
                "Attestation that the holder has been fully vaccinated against COVID-19",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "RA01_test",
                "Test event for COVID-19",
                "Attestation that the holder has obtained a negative test for COVID-19",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "safeEntry_Leisure",
                "Safe entry indication",
                "Attest that the holder fulfils certain set requirements for safe entry in a leisure context (without disclosing if it is based on vaccination, recovery, or negative test)",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "fac",
                "Facial image",
                "Facial Image of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "fni",
                "Family name initial",
                "Initial letter of the Family Name of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "gni",
                "Given name initial",
                "Initial letter of the Given Name of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "by",
                "Birth year",
                "Birth Year of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "bm",
                "Birth month",
                "Birth Month of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "bd",
                "Birth day",
                "Birth Day of the holder",
                false,
                MICOV_ATT_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "fn",
                "Family name",
                "Family Name of the holder",
                true,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "gn",
                "Given name",
                "Given Name of the holder",
                true,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "dob",
                "Date of birth",
                "Date of Birth of the holder",
                true,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "sex",
                "Sex",
                "Sex",
                false,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "v_RA01_1",
                "RA01 first vaccination",
                "COVID-19 – first vaccination data",
                false,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "v_RA01_2",
                "RA01 second vaccination",
                "COVID-19 – second vaccination data",
                false,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "pid_PPN",
                "ID with passport number",
                "Unique set of elements identifying the holder by passport number",
                false,
                MICOV_VTR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "pid_DL",
                "ID with driver’s license number",
                "Unique set of elements identifying the holder by driver’s license number",
                false,
                MICOV_VTR_NAMESPACE
            )
            .build()
    }
}