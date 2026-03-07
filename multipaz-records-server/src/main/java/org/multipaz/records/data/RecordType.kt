package org.multipaz.records.data

import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.DataItem
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon

/**
 * A class that describes the type of particular record or field.
 */
class RecordType(
    val attribute: DocumentAttribute,
    val subAttributes: Map<String, RecordType> = mapOf()
) {
    // List is represented by an "attribute" with the name "*"
    val isList: Boolean get() =
        attribute.type == DocumentAttributeType.ComplexType && subAttributes.containsKey("*")

    val listElement: RecordType get() = subAttributes["*"]!!

    class Builder internal constructor(
        val identifier: String
    ) {
        internal val subAttributes = mutableMapOf<String, RecordType>()
        var displayName: String? = null
        var description: String? = null
        var icon: Icon? = null
        var sampleValueMdoc: DataItem? = null
        var sampleValueJson: JsonElement? = null

        fun addPrimitive(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            check(!subAttributes.containsKey(identifier))
            subAttributes[identifier] = RecordType(
                attribute = DocumentAttribute(
                    type = type,
                    identifier = identifier,
                    displayName = displayName,
                    description = description,
                    icon = icon
                ),
                subAttributes = mapOf()
            )
        }

        fun addString(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            addPrimitive(DocumentAttributeType.String, identifier, displayName, description, icon)
        }

        fun addNumber(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            addPrimitive(DocumentAttributeType.Number, identifier, displayName, description, icon)
        }

        fun addDate(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            addPrimitive(DocumentAttributeType.Date, identifier, displayName, description, icon)
        }

        fun addDateTime(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            addPrimitive(DocumentAttributeType.DateTime, identifier, displayName, description, icon)
        }

        fun addPicture(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
        ) {
            addPrimitive(DocumentAttributeType.Picture, identifier, displayName, description, icon)
        }

        fun addComplex(identifier: String, block: Builder.() -> Unit) {
            check(!subAttributes.containsKey(identifier))
            val builder = Builder(identifier)
            block.invoke(builder)
            subAttributes[identifier] = builder.build()
        }

        fun addPrimitiveList(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null
        ) {
            addComplex(identifier) {
                this.displayName = displayName
                this.description = description
                this.icon = icon
                addPrimitive(type, "*", "", "")
            }
        }

        fun addComplexList(
            identifier: String,
            displayName: String,
            description: String,
            icon: Icon? = null,
            block: Builder.() -> Unit
        ) {
            addComplex(identifier) {
                this.displayName = displayName
                this.description = description
                this.icon = icon
                addComplex("*", block)
            }
        }

        fun build(): RecordType {
            val attribute = DocumentAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = identifier,
                displayName = displayName ?: identifier,
                description = description ?: displayName ?: identifier,
                icon = icon,
                sampleValueMdoc = sampleValueMdoc,
                sampleValueJson = sampleValueJson,
                parentAttribute = null,
                embeddedAttributes = subAttributes.values.map { it.attribute }
            )
            return RecordType(attribute, subAttributes.toMap())
        }
    }

    companion object {
        fun buildMap(block: Builder.() -> Unit): Map<String, RecordType> {
            val builder = Builder("")
            block.invoke(builder)
            return builder.subAttributes.toMap()
        }
    }
}

