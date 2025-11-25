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
 * Class containing the metadata of an ISO mdoc Document Type.
 *
 * @property docType the ISO mdoc doc type e.g. `org.iso.18013.5.1.mDL`.
 * @property namespaces the namespaces of the doc type.
 */
class MdocDocumentType private constructor(
    val docType: String,
    val namespaces: Map<String, MdocNamespace>
) {
    /**
     * Builder class for class [MdocDocumentType].
     *
     * @param docType the docType of the ISO mdoc Document Type.
     */
    data class Builder(
        val docType: String,
        internal val namespaces: MutableMap<String, MdocNamespace.Builder> = mutableMapOf(),
    ) {
        /**
         * Add a data element to a namespace in the ISO mdoc Document Type.
         *
         * @param namespace the namespace of the ISO mdoc attribute.
         * @param type the datatype of this attribute.
         * @param identifier the identifier of this attribute.
         * @param displayName the name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param mandatory indication whether the ISO mdoc attribute is mandatory.
         * @param icon the icon, if available.
         * @param sampleValue a sample value for the attribute, if available.
         * @return the builder.
         */
        fun addDataElement(
            namespace: String,
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            mandatory: Boolean,
            icon: Icon? = null,
            sampleValue: DataItem? = null,
        ) = apply {
            if (!namespaces.containsKey(namespace)) {
                namespaces[namespace] = MdocNamespace.Builder(namespace)
            }
            namespaces[namespace]!!.addDataElement(
                type,
                identifier,
                displayName,
                description,
                mandatory,
                icon,
                sampleValue
            )
        }

        /**
         * Adds an existing namespace to this type.
         *
         * @param namespace the existing namespace to add.
         * @return the builder.
         */
        fun addNamespace(
            namespace: MdocNamespace
        ) = apply {
            namespace.dataElements.forEach { (dataElementName, dataElement) ->
                addDataElement(
                    namespace = namespace.namespace,
                    type = dataElement.attribute.type,
                    identifier = dataElement.attribute.identifier,
                    displayName = dataElement.attribute.displayName,
                    description = dataElement.attribute.description,
                    mandatory = dataElement.mandatory,
                    icon = dataElement.attribute.icon,
                    sampleValue = dataElement.attribute.sampleValueMdoc
                )
            }
        }

        /**
         * Build the [MdocDocumentType].
         */
        fun build() =
            MdocDocumentType(docType, namespaces.map { Pair(it.key, it.value.build()) }.toMap())
    }
}