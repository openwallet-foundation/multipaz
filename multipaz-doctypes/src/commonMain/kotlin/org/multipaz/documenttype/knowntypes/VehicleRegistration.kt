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
 * Object containing the metadata of the Vehicle Registration
 * Document Type.
 */

object VehicleRegistration {
    const val MVR_NAMESPACE = "nl.rdw.mekb.1"

    /**
     * Build the Vehicle Registration Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Vehicle registration")
            .addMdocDocumentType("nl.rdw.mekb.1")
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "registration_info",
                "Vehicle registration information",
                "This data element contains the common vehicle registration information, including UN/EU elements, A and H.",
                true,
                MVR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                "Issue date",
                "Date when document was issued",
                true,
                MVR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "registration_holder",
                "Vehicle registration holder information",
                "This data element identifies the holder of the registration certificate",
                true,
                MVR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.ComplexType,
                "basic_vehicle_info",
                "Basic vehicle information",
                "This data element contains the basic vehicle information",
                true,
                MVR_NAMESPACE
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "vin",
                "Vehicle identification number",
                "Vehicle Identification Number defined by the vehicle manufacture",
                true,
                MVR_NAMESPACE
            )
            .build()
    }
}