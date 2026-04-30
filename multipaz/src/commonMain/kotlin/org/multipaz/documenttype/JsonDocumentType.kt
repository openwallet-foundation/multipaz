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

import kotlinx.serialization.json.JsonElement

/**
 * Class containing the metadata of an JSON-based Document Type.
 *
 * @property vct the document type e.g. `urn:eudi:pid:1`.
 * @property claims the claims for the document type. Each claim name uses `.` to separate path components
 *   for example `age_equal_or_over.18`.
 * @property keyBound `true` if a device-bound key must exist for the document.
 */
class JsonDocumentType private constructor(
    val vct: String,
    val claims: Map<String, DocumentAttribute>,
    val keyBound: Boolean
) {

    /**
     * Looks up a JSON attribute.
     *
     * @param identifier the identifier of an attribute for JSON-based credentials using `.` to separate
     *   path components, e.g. `age_equal_or_over.18`.
     * @return the document attribute or `null` if not found.
     */
    fun getDocumentAttribute(identifier: String): DocumentAttribute? {
        val splits = identifier.split(".")
        return when (splits.size ) {
            1 -> claims[splits[0]]
            2 -> claims[splits[0]]?.embeddedAttributes?.find { it.identifier == splits[1] }
            else -> throw Exception("Invalid identifier $identifier, can have at max one period")
        }
    }

    /**
     * Builder class for class [JsonDocumentType].
     *
     * @param vct the document type e.g. `urn:eudi:pid:1`.
     * @param keyBound `true` if a device-bound key must exist for the document.
     */
    data class Builder(
        val vct: String,
        val keyBound: Boolean = true,
        internal val claims: MutableMap<String, Pair<DocumentAttribute, MutableList<DocumentAttribute>>> = mutableMapOf(),
    ) {
        /**
         * Add a claim to the metadata of the JSON-based Document Type.
         *
         * @param type the datatype of this claim.
         * @param identifier the identifier of this claim.
         * @param displayName a name suitable for display of the claim.
         * @param description a description of the claim.
         * @param icon the icon, if available.
         * @param sampleValue a sample value for the attribute, if available.
         */
        fun addClaim(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
            sampleValue: JsonElement? = null
        ) = apply {
            val embeddedAttributes = mutableListOf<DocumentAttribute>()
            val attribute = DocumentAttribute(
                type = type,
                identifier = identifier,
                displayName = displayName,
                description = description,
                icon = icon,
                sampleValueMdoc = null,
                sampleValueJson = sampleValue,
                parentAttribute = null,
                embeddedAttributes = embeddedAttributes
            )
            claims[identifier] = Pair(attribute, embeddedAttributes)
        }

        internal fun addEmbeddedAttribute(
            parentIdentifier: String,
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
            icon: Icon? = null,
            sampleValue: JsonElement? = null
        ) {
            val (parentAttribute, embeddedAttributes) = claims[parentIdentifier]
                ?: throw IllegalStateException("No attribute for $parentIdentifier")
            embeddedAttributes.add(
                DocumentAttribute(
                    type = type,
                    identifier = identifier,
                    displayName = displayName,
                    description = description,
                    sensitivity = sensitivity,
                    icon = icon,
                    sampleValueMdoc = null,
                    sampleValueJson = sampleValue,
                    parentAttribute = parentAttribute
                )
            )
        }

        /**
         * Build the [JsonDocumentType].
         */
        fun build() = JsonDocumentType(vct, claims.mapValues { (_, v) -> v.first }, keyBound)
    }
}