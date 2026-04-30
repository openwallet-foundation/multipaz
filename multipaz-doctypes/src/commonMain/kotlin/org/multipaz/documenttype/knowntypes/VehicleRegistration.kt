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
 * Object containing the metadata of the Vehicle Registration
 * Document Type.
 */

object VehicleRegistration {
    const val MVR_NAMESPACE = "nl.rdw.mekb.1"

    /**
     * Build the Vehicle Registration Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_VEHICLE_REGISTRATION))
            .addMdocDocumentType("nl.rdw.mekb.1")
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "registration_info",
                displayName = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_ATTRIBUTE_REGISTRATION_INFO),
                description = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_DESCRIPTION_REGISTRATION_INFO),
                mandatory = true,
                mdocNamespace = MVR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issue_date",
                displayName = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_ATTRIBUTE_ISSUE_DATE),
                description = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_DESCRIPTION_ISSUE_DATE),
                mandatory = true,
                mdocNamespace = MVR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "registration_holder",
                displayName = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_ATTRIBUTE_REGISTRATION_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_DESCRIPTION_REGISTRATION_HOLDER),
                mandatory = true,
                mdocNamespace = MVR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "basic_vehicle_info",
                displayName = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_ATTRIBUTE_BASIC_VEHICLE_INFO),
                description = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_DESCRIPTION_BASIC_VEHICLE_INFO),
                mandatory = true,
                mdocNamespace = MVR_NAMESPACE
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "vin",
                displayName = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_ATTRIBUTE_VIN),
                description = getLocalizedString(GeneratedStringKeys.VEHICLE_REGISTRATION_DESCRIPTION_VIN),
                mandatory = true,
                mdocNamespace = MVR_NAMESPACE
            )
            .build()
    }
}
