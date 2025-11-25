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

package org.multipaz.documenttype

import org.multipaz.cbor.DataItem

/**
 * Class containing the metadata of a namespace in an ISO mdoc Document Type.
 *
 * @param namespace the namespace of this part of the ISO mdoc Document Type.
 * @param dataElements the data elements in this namespace.
 */
class MdocNamespace private constructor(
    val namespace: String,
    val dataElements: Map<String, MdocDataElement>
) {
    /**
     * Builder class for class [MdocNamespace].
     *
     * @param namespace the namespace of this part of the ISO mdoc Document Type.
     * @param dataElements the data elements in this namespace.
     */
    data class Builder(
        val namespace: String,
        val dataElements: MutableMap<String, MdocDataElement> = mutableMapOf()
    ) {

        /**
         * Add a data element to a namespace in the ISO mdoc Document Type.
         *
         * @param type the datatype of this attribute.
         * @param identifier the identifier of this attribute.
         * @param displayName the name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param mandatory indication whether the mdoc attribute is mandatory.
         * @param icon the icon, if available.
         * @param sampleValue a sample value for the attribute, if available.
         */
        fun addDataElement(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            mandatory: Boolean,
            icon: Icon?,
            sampleValue: DataItem?,
        ) = apply {
            dataElements[identifier] = MdocDataElement(
                DocumentAttribute(
                    type = type,
                    identifier = identifier,
                    displayName = displayName,
                    description = description,
                    icon = icon,
                    sampleValueMdoc = sampleValue,
                    sampleValueJson = null,
                    parentAttribute = null,
                    embeddedAttributes = emptyList()
                ),
                mandatory
            )
        }

        /**
         * Build the [MdocNamespace].
         */
        fun build() = MdocNamespace(namespace, dataElements)
    }
}