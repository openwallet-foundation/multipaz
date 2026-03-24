package org.multipaz.documenttype

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.presentment.TransactionData

/**
 * An object that represents a particular transaction data type.
 *
 * All transaction types that are expected to be processed or rejected must be registered in a
 * [DocumentTypeRepository] object. In OpenID4VP unregistered transaction types cause the whole
 * request to be rejected. In ISO/IEC 18013-5:2021, unknown transaction types are not processed,
 * which may or may not fail at verification time.
 *
 * @param displayName human-readable transaction name
 * @param identifier unique transaction type identifier, corresponds to the `type` property in
 *  transaction data in OpenID4VP; all [TransactionType] objects must have distinct identifiers.
 * @param attributes describes attributes that this transaction type can/must contain
 * @param kbJwtResponseClaimName if transaction processing results in any data, it will be inserted
 *  in key binding JWT using this claim name; all [TransactionType] objects must have distinct
 *  values.
 * @param mdocRequestInfoKeyName key to use in `requestInfo` map in ISO/IEC 18013-5:2021 document
 *  request to represent this transaction data; all [TransactionType] objects must have distinct
 *  values.
 * @param mdocResponseNamespace namespace to use in `deviceSigned` namespace map in
 *  ISO/IEC 18013-5:2021 response to represent transaction hash and transaction processing
 *  results; all [TransactionType] objects must have distinct values.
 */
abstract class TransactionType(
    val displayName: String,
    val identifier: String,
    attributes: List<MdocDataElement>,
    val kbJwtResponseClaimName: String = identifier,
    val mdocRequestInfoKeyName: String = identifier,
    val mdocResponseNamespace: String = identifier,
) {
    /** Maps transaction data property name to its definition */
    val dataElements: Map<String, MdocDataElement> = attributes.associateBy {
        it.attribute.identifier
    }

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * When transaction cannot be processed, it removes a particular "use case" or credential
     * set option from consideration. If other options are available, presentment still may
     * succeed.
     *
     * @param transactionData transaction data being considered
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed false if it cannot
     */
    abstract suspend fun isApplicable(
        transactionData: TransactionData,
        credential: Credential
    ): Boolean

    /**
     * Applies transaction in the context of ISO ISO/IEC 18013-5:2021 presentment.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in `deviceSigned`
     *  namespace map using [mdocResponseNamespace]), `null` if no extra data should be added.
     */
    open suspend fun applyCbor(
        transactionData: TransactionData,
        credential: Credential
    ): Map<String, DataItem>? {
        return null
    }

    /**
     * Applies transaction in the context of OpenID4VP presentment.
     *
     * Default implementation adds the same values as [applyCbor], which is the recommended
     * behavior.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in key-binding
     *  JWT body using [kbJwtResponseClaimName]), `null` if no extra data should be added.
     */
    open suspend fun applyJson(
        transactionData: TransactionData,
        credential: Credential
    ): JsonElement? {
        val extra = applyCbor(transactionData, credential) ?: return null
        return buildJsonObject {
            for ((key, value) in extra) {
                put(key, value.toJson())
            }
        }
    }
}