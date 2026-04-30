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
import kotlinx.serialization.json.JsonElement

/**
 * Class containing the metadata of an attribute/data element/claim of a Document Type
 *
 * @property type the datatype of this attribute.
 * @property identifier the identifier of this attribute.
 * @property displayName the name suitable for display of the attribute.
 * @property description a description of the attribute.
 * @property sensitivity the sensitivity of the attribute.
 * @property icon the icon for the attribute, if available.
 * @property sampleValueMdoc a sample value for the attribute for ISO mdoc credentials, if available.
 * @property sampleValueJson a sample value for the attribute for JSON-based credentials, if available.
 * @property parentAttribute the parent attribute or `null` if this is not an embedded attribute.
 * @property embeddedAttributes attributes embedded in this attribute, only applicable for JSON-based credentials.
 */
data class DocumentAttribute(
    val type: DocumentAttributeType,
    val identifier: String,
    val displayName: String,
    val description: String,
    val sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
    val icon: Icon? = null,
    val sampleValueMdoc: DataItem? = null,
    val sampleValueJson: JsonElement? = null,
    val parentAttribute: DocumentAttribute? = null,
    val embeddedAttributes: List<DocumentAttribute> = emptyList()
) {
    // NOTE: we ignore embeddedAttributes to avoid hashCode() causing infinite loops
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + identifier.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + (sampleValueMdoc?.hashCode() ?: 0)
        result = 31 * result + (sampleValueJson?.hashCode() ?: 0)
        result = 31 * result + (parentAttribute?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "DocumentAttribute(" +
                "type=$type, " +
                "identifier='$identifier', " +
                "displayName='$displayName', " +
                "description='$description', " +
                "icon=$icon, " +
                "sampleValueMdoc=$sampleValueMdoc, " +
                "sampleValueJson=$sampleValueJson, " +
                // Prevent Parent loop: Only print the parent's ID, not the whole object
                "parentAttribute=${parentAttribute?.identifier ?: "null"}, " +
                // Prevent Child loop: Print only the size of the list
                "embeddedAttributes=[size=${embeddedAttributes.size}])"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DocumentAttribute

        if (type != other.type) return false
        if (identifier != other.identifier) return false
        if (displayName != other.displayName) return false
        if (description != other.description) return false
        if (icon != other.icon) return false
        if (sampleValueMdoc != other.sampleValueMdoc) return false
        if (sampleValueJson != other.sampleValueJson) return false
        if (parentAttribute != other.parentAttribute) return false
        if (embeddedAttributes != other.embeddedAttributes) return false
        return true
    }
}