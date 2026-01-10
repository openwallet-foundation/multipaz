package org.multipaz.mdoc.response

import org.multipaz.document.Document
import org.multipaz.document.NameSpacedData
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.document.AbstractDocumentMetadata

class TestDocumentMetadata(
    var nameSpacedData: NameSpacedData
) : AbstractDocumentMetadata {
    override fun serialize() = ByteString(nameSpacedData.encodeAsCbor())

    companion object {
        suspend fun create(
            documentId: String,
            serializedData: ByteString?,
        ): TestDocumentMetadata {
            val nameSpacedData = if (serializedData == null || serializedData.isEmpty()) {
                NameSpacedData.Builder().build()
            } else {
                NameSpacedData.fromEncodedCbor(serializedData.toByteArray())
            }
            return TestDocumentMetadata(nameSpacedData)
        }
    }
}

val Document.testMetadata: TestDocumentMetadata
    get() = metadata as TestDocumentMetadata
