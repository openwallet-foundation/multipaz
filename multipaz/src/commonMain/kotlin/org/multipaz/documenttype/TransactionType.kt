package org.multipaz.documenttype

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url

/**
 * Namespace defined by ISO/IEC 18013-5 for transaction data signing.
 */
const val ISO_18013_TRANSACTION_DATA_NAMESPACE = "org.iso.transactiondata"

/**
 * Represents a transaction data type used for dynamic linking and transaction authorization in
 * digital credential presentations.
 *
 * Dynamic linking cryptographically binds a presentation to a specific transaction context
 * (such as payment amount, currency, payee, or nonce) and optional user input (such as tip amount),
 * ensuring the credential holder explicitly authorizes the transaction and protecting against
 * relay, replay, and man-in-the-middle attacks.
 *
 * ### Supported Protocols and Credential Formats
 *
 * Transaction processing supports both major presentment protocols and credential formats across four
 * distinct combinations:
 *
 * 1. **ISO/IEC 18013-5 with ISO mdoc (`mso_mdoc`)**: The verifier sends transaction data inside the
 *    `requestInfo.transactionData` map of an ISO 18013-5 `DeviceRequest`. The wallet returns transaction
 *    response elements (e.g. `amount`, `currency`, and `tipAmount`) nested inside a CBOR map under the
 *    transaction [identifier] within the standard namespace ([ISO_18013_TRANSACTION_DATA_NAMESPACE]) in
 *    `DeviceSigned.nameSpaces`, bound to the request via `docRequestId`.
 *
 * 2. **ISO/IEC 18013-5 with SD-JWT VC (`dc+sd-jwt`)**: The verifier requests an SD-JWT VC within an
 *    ISO 18013-5 `DeviceRequest`. The wallet generates a Key Binding JWT (KB-JWT) where transaction
 *    processing response claims are placed under [kbJwtResponseClaimName] (along with `doc_request_id`).
 *
 * 3. **OpenID4VP with SD-JWT VC (`dc+sd-jwt`)**: The verifier supplies `transaction_data` in the
 *    OpenID4VP authorization request. The wallet hashes the transaction payload and inserts
 *    `transaction_data_hashes_alg` and `transaction_data_hashes` (along with user input claims) directly
 *    into the SD-JWT KB-JWT payload.
 *
 * 4. **OpenID4VP with ISO mdoc (`mso_mdoc`)**: The verifier supplies `transaction_data` in the
 *    OpenID4VP authorization request. The wallet places transaction evidence directly in
 *    `DeviceSigned.nameSpaces` under [openId4VpMdocResponseNamespace] (defaulting to [identifier]),
 *    returning top-level data elements for the computed hash (`transactionDataHash`), hash algorithm
 *    (`transactionDataHashAlg`), and user input (`tipAmount`).
 *
 * ### Lifecycle & Verification
 *
 * All transaction types expected to be processed or rejected must be registered in a
 * [DocumentTypeRepository].
 * - **Applicability ([isApplicable])**: Validates that candidate credentials authorize the device key to
 *   sign under the protocol's designated namespace ([ISO_18013_TRANSACTION_DATA_NAMESPACE] for ISO 18013-5, or
 *   [openId4VpMdocResponseNamespace] for OpenID4VP) in the Mobile Security Object (MSO).
 * - **Generation ([generateMdocResponseElements], [generateSdJwtResponseClaims])**: Invoked during wallet
 *   presentment to populate device-signed elements or KB-JWT claims based on the transaction payload and
 *   optional [TransactionUserInput].
 * - **Verification ([verifyMdocResponse], [verifySdJwtResponse])**: Invoked on the verifier side during
 *   document authentication to validate that returned amounts, currencies, user inputs, and transaction
 *   hashes match the requested transaction.
 *
 * @param PayloadT type of the transaction-specific payload data.
 * @param displayName human-readable transaction name.
 * @param identifier unique transaction type identifier, corresponds to the `type` property in
 *  transaction data in OpenID4VP; all [TransactionType] objects must have distinct identifiers.
 * @param kbJwtResponseClaimName if transaction processing results in any data, it will be inserted
 *  in key binding JWT using this claim name; all [TransactionType] objects must have distinct values.
 * @param iso18013RequestInfoIdentifier transaction type to use in `transactionData` map in
 *  `requestInfo` map in ISO/IEC 18013-5 document request to represent this transaction data;
 *  all [TransactionType] objects must have distinct values.
 * @param openId4VpMdocResponseNamespace namespace to use in `deviceSigned` namespace map in
 *  OpenID4VP response; defaults to [identifier].
 */
abstract class TransactionType<PayloadT: Any>(
    val displayName: String,
    val identifier: String,
    val kbJwtResponseClaimName: String = identifier,
    val iso18013RequestInfoIdentifier: String = identifier,
    val openId4VpMdocResponseNamespace: String = identifier,
) {
    /**
     * Returns the DeviceSigned namespace to use for the given presentment protocol.
     */
    fun getMdocResponseNamespace(protocol: TransactionProtocol): String = when (protocol) {
        TransactionProtocol.ISO_18013_5 -> ISO_18013_TRANSACTION_DATA_NAMESPACE
        TransactionProtocol.OPENID4VP -> openId4VpMdocResponseNamespace
    }

    /**
     * Serializes transaction data for use in OpenID4VP protocol.
     *
     * @param payload transaction-specific data
     * @param credentialIds list of DCQL credential ids to which this transaction should be applied
     * @param hashAlgorithms optional list of hash algorithms that are accepted by the verifier
     * @return JSON-serialized (but **not** Base64Url-encoded!) transaction data
     */
    open fun serializeOpenId4VpRequest(
        payload: PayloadT,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>? = null
    ): String = throw UnsupportedOperationException("serializeOpenId4VpRequest not implemented for '$identifier'")

    /**
     * Serializes transaction data for use in ISO/IEC 18013 protocols.
     *
     * @param payload transaction-specific data
     * @return serialized transaction data as a CBOR map (TransactionDataContent)
     */
    open fun serializeIso18013Request(payload: PayloadT): DataItem =
        throw UnsupportedOperationException("serializeIso18013Request not implemented for '$identifier'")

    /**
     * Parses transaction data serialized for use in OpenID4VP protocol.
     *
     * @param jsonString parsed JSON string from base64url-encoded OpenID4VP transaction_data
     * @return transaction payload
     */
    open fun parseOpenId4VpRequest(jsonString: String): PayloadT =
        throw UnsupportedOperationException("parseOpenId4VpRequest not implemented for '$identifier'")

    /**
     * Parses transaction data serialized for use in ISO/IEC 18013 protocols.
     *
     * @param dataItem value of the transaction data item in `transactionData` inside `requestInfo`
     * @return transaction payload
     */
    open fun parseIso18013Request(dataItem: DataItem): PayloadT =
        throw UnsupportedOperationException("parseIso18013Request not implemented for '$identifier'")


    /**
     * Parses transaction data serialized for use in OpenID4VP protocol.
     */
    open fun parseJson(serialized: ByteString): TransactionData<PayloadT> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        return TransactionData(
            type = this,
            payload = parseOpenId4VpRequest(jsonString),
            protocol = TransactionProtocol.OPENID4VP,
            rawBytes = serialized,
        )
    }

    /**
     * Parses transaction data serialized for use in ISO/IEC 18013-5 presentment.
     *
     * @param serialized the CBOR data item representing the transaction request.
     * @return transaction data wrapping the parsed payload.
     */
    open fun parseCbor(serialized: DataItem): TransactionData<PayloadT> {
        return TransactionData(
            type = this,
            payload = parseIso18013Request(serialized),
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(serialized)),
        )
    }

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * When transaction cannot be processed, it removes a particular "use case" or credential
     * set option from consideration. If other options are available, presentment still may
     * succeed.
     *
     * For mdoc credentials this method checks whether the MSO authorizes the device key for
     * [ISO_18013_TRANSACTION_DATA_NAMESPACE] in [MobileSecurityObject.deviceKeyAuthorizedNamespaces] or
     * for [identifier] in [MobileSecurityObject.deviceKeyAuthorizedDataElements].
     *
     * @param transactionData transaction data being considered
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed false if it cannot
     */
    open suspend fun isApplicable(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Boolean {
        return if (credential is MdocCredential) {
            // For mdoc there is a per-credential KeyAuthorizations section. We need to check
            // it to determine if this transaction can be applied to this credential
            val expectedNamespace = getMdocResponseNamespace(transactionData.protocol)
            when (transactionData.protocol) {
                TransactionProtocol.ISO_18013_5 -> {
                    credential.mso.deviceKeyAuthorizedNamespaces.contains(expectedNamespace) ||
                        credential.mso.deviceKeyAuthorizedDataElements[expectedNamespace]?.contains(identifier) == true
                }
                TransactionProtocol.OPENID4VP -> {
                    credential.mso.deviceKeyAuthorizedNamespaces.contains(expectedNamespace) ||
                        credential.mso.deviceKeyAuthorizedDataElements[expectedNamespace] != null
                }
            }
        } else {
            true
        }
    }

    /**
     * Generates device-signed data elements for an Mdoc credential.
     *
     * Used for Case 1 (ISO 18013-5) and Case 4 (OpenID4VP).
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @param userInput additional data specified by the user
     * @param docRequestId document request index in ISO 18013-5, null in OpenID4VP
     * @return map of data elements for `DeviceSigned.nameSpaces["org.iso.transactiondata"][identifier]`
     */
    open suspend fun generateMdocResponseElements(
        transactionData: TransactionData<PayloadT>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int? = null
    ): Map<String, DataItem> = buildMap {
        userInput?.generateMdocResponseElements(transactionData, credential)?.let { putAll(it) }
        if (transactionData.protocol == TransactionProtocol.OPENID4VP) {
            val alg = transactionData.hashAlgorithms?.first()?.also {
                put("transactionDataHashAlg", it.coseAlgorithmIdentifier!!.toDataItem())
            }
            put("transactionDataHash",
                transactionData.computeHash(alg ?: Algorithm.SHA256).toByteArray().toDataItem())
        } else {
            docRequestId?.let { put("docRequestId", it.toDataItem()) }
        }
    }

    /**
     * Generates Key Binding JWT claims for an SD-JWT credential.
     *
     * Used for Case 2 (ISO 18013-5) and Case 3 (OpenID4VP).
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @param userInput additional data specified by the user
     * @param docRequestId document request index in ISO 18013-5, null in OpenID4VP
     * @return map of claims to include in the KB-JWT payload
     */
    open suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<PayloadT>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int? = null
    ): Map<String, JsonElement> = buildMap {
        userInput?.generateSdJwtResponseClaims(transactionData, credential)?.let { putAll(it) }
        if (transactionData.protocol == TransactionProtocol.ISO_18013_5) {
            docRequestId?.let { put("doc_request_id", JsonPrimitive(it)) }
        }
    }

    /**
     * Verifies transaction response returned in an Mdoc presentation.
     */
    open suspend fun verifyMdocResponse(
        transactionData: TransactionData<PayloadT>,
        responseElements: Map<String, DataItem>
    ) {
        if (transactionData.protocol == TransactionProtocol.OPENID4VP) {
            val hashAlg = responseElements["transactionDataHashAlg"]?.let {
                Algorithm.fromCoseAlgorithmIdentifier(it.asNumber.toInt())
            }
            val hash = responseElements["transactionDataHash"] as? Bstr
                ?: throw IllegalStateException("Invalid response for transaction '$identifier'")
            val expectedHash = transactionData.computeHash(hashAlg ?: Algorithm.SHA256)
            if (ByteString(hash.asBstr) != expectedHash) {
                throw IllegalStateException("Transaction hash failed to verify for '$identifier'")
            }
        }
    }

    /**
     * Verifies transaction response returned in an SD-JWT presentation.
     */
    open suspend fun verifySdJwtResponse(
        transactionData: TransactionData<PayloadT>,
        responseClaims: Map<String, JsonElement>
    ) {
    }

    companion object {
        private const val TAG = "TransactionType"

        /**
         * Converts the list of algorithms for use in ISO/IEC 18013 protocols.
         *
         * Only algorithms with well-defined COSE identifiers are passed through
         *
         * @return list of algorithms as COSE identifiers
         * @throws IllegalArgumentException if no algorithms can be represented
         */
        fun coseHashAlgorithms(transactionDataHashesAlg: List<Algorithm>?): List<Long>? =
            transactionDataHashesAlg
                ?.mapNotNull { it.coseAlgorithmIdentifier?.toLong() }
                ?.ifEmpty { throw IllegalArgumentException("No valid hash algorithms") }

        /**
         * Converts the list of algorithms for use in OpenID4VP protocol.
         *
         * Only algorithms with well-defined JOSE identifiers are passed through
         *
         * @return list of algorithms as JOSE identifiers
         * @throws IllegalArgumentException if no algorithms can be represented
         */
        fun joseHashAlgorithms(transactionDataHashesAlg: List<Algorithm>?): List<String>? =
            transactionDataHashesAlg
                ?.mapNotNull { it.hashAlgorithmName ?: it.joseAlgorithmIdentifier }
                ?.ifEmpty { throw IllegalArgumentException("No valid hash algorithms") }

        /**
         * Parses the list of JOSE algorithm identifiers.
         *
         * Unknown algorithm identifiers are skipped (and logged).
         *
         * @return list of algorithms
         * @throws IllegalArgumentException if there are no known algorithms in the list
         */
        fun parseJoseHashAlgorithms(transactionDataHashesAlg: List<String>?): List<Algorithm>? =
            transactionDataHashesAlg?.mapNotNull {
                try {
                    Algorithm.fromHashAlgorithmIdentifier(it)
                } catch (err: IllegalArgumentException) {
                    Logger.e(TAG, "Unknown hash algorithm '$it'", err)
                    null
                }
            }?.ifEmpty {
                throw IllegalArgumentException("No valid hash algorithms")
            }

        /**
         * Parses the list of COSE algorithm identifiers.
         *
         * Unknown algorithm identifiers are skipped (and logged).
         *
         * @return list of algorithms
         * @throws IllegalArgumentException if there are no known algorithms in the list
         */
        fun parseCoseHashAlgorithms(transactionDataHashesAlg: List<Long>?): List<Algorithm>? =
            transactionDataHashesAlg?.mapNotNull {
                try {
                    Algorithm.fromCoseAlgorithmIdentifier(it.toInt())
                } catch (err: IllegalArgumentException) {
                    Logger.e(TAG, "Unknown hash algorithm '$it'", err)
                    null
                }
            }?.ifEmpty {
                throw IllegalArgumentException("No valid hash algorithms")
            }
    }
}