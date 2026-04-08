package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.MdocDataElement
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential

/**
 * Transaction type that round-trips some data through the presentment process for testing.
 */
object PingTransaction: TransactionType(
    displayName = "Ping",
    identifier = "org.multipaz.transaction.ping",
    attributes = listOf(
        MdocDataElement(
            attribute = DocumentAttribute(
                identifier = "string",
                type = DocumentAttributeType.String,
                displayName = "String data",
                description = "String data to round-trip"
            ),
            mandatory = false
        ),
        MdocDataElement(
            attribute = DocumentAttribute(
                identifier = "blob",
                type = DocumentAttributeType.Blob,
                displayName = "Binary data",
                description = "Binary data to round-trip"
            ),
            mandatory = false
        )
    )
) {
    override suspend fun isApplicable(
        transactionData: TransactionData,
        credential: Credential
    ): Boolean {
        // For the sake of testing, refuse UtopiaNaturalization
        return !(credential is KeyBoundSdJwtVcCredential
                && credential.vct == UtopiaNaturalization.VCT)
    }

    override suspend fun applyCbor(
        transactionData: TransactionData,
        credential: Credential
    ): Map<String, DataItem> {
        return buildMap {
            transactionData.getString("string")?.let {
                put("string", it.toDataItem())
            }
            transactionData.getBlob("blob")?.let {
                put("blob", it.toByteArray().toDataItem())
            }
        }
    }

    /** Sample transaction data for this transaction type */
    val sampleData = buildCanned {
        put("string", "string data")
    }
}