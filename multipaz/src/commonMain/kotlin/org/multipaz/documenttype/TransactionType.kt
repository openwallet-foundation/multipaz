package org.multipaz.documenttype

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import kotlin.coroutines.cancellation.CancellationException

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
 * - **Generation ([generateMdocResponseElements], [generateSdJwtResponse])**: Invoked during wallet
 *   presentment to populate device-signed elements or KB-JWT claims (and disclosures that follow the
 *   KB-JWT) based on the transaction payload and optional [TransactionUserInput].
 *   [generateSdJwtResponse] by default returns the claims of [generateSdJwtResponseClaims]; override
 *   [generateSdJwtResponseClaims] when only claims are needed and [generateSdJwtResponse] when the
 *   type also returns disclosures.
 * - **Verification ([verifyMdocResponse], [verifySdJwtResponse])**: Invoked on the verifier side during
 *   document authentication to validate that returned amounts, currencies, user inputs, and transaction
 *   hashes match the requested transaction.
 *
 * ### Controlling the SD-JWT response (OpenID4VP only)
 *
 * A specification that fixes the shape of its own KB-JWT, for example a Delegated SD-JWT, uses
 * [nestSdJwtResponseClaims], [sdJwtKbType], [validateTogether] and [generateSdJwtResponse] together:
 * returning disclosures from [generateSdJwtResponse] requires [sdJwtKbType] to return a `typ` other
 * than `kb+jwt`. A type that can be answered over OpenID4VP only returns `false` from [isApplicable]
 * when the transaction data's protocol is [TransactionProtocol.ISO_18013_5]; a type that does not
 * implement [parseIso18013Request] never receives ISO/IEC 18013-5 transaction data.
 *
 * Only [isApplicable], [validateTogether] and the check that the types of a request agree on
 * [sdJwtKbType] refuse a request before the consent prompt. What [generateSdJwtResponse] returns is
 * checked when the response is built, after the person has approved: that disclosures come with their
 * own `typ`, that no claim collides with another, and that no disclosure is returned twice.
 *
 * The example parses and serializes the request itself (the defaults of [parseOpenId4VpRequest] and
 * [serializeOpenId4VpRequest] do not), and is registered with
 * [DocumentTypeRepository.addTransactionType]:
 * ```
 * object MandateTransaction : TransactionType<Mandate>(
 *     displayName = "Mandate", identifier = "mandate",
 *     nestSdJwtResponseClaims = false,
 * ) {
 *     override fun parseOpenId4VpRequest(jsonString: String): Mandate = Mandate.fromJson(jsonString)
 *
 *     override fun serializeOpenId4VpRequest(
 *         payload: Mandate, credentialIds: List<String>, hashAlgorithms: List<Algorithm>?
 *     ): String = payload.toJson(credentialIds)
 *
 *     override fun sdJwtKbType(transactionData: List<TransactionData<Mandate>>) = "kb+sd-jwt"
 *
 *     override fun validateTogether(transactionData: List<TransactionData<Mandate>>) {
 *         require(transactionData.size == 1) { "Only one mandate per response" }
 *     }
 *
 *     override suspend fun generateSdJwtResponse(
 *         transactionData: TransactionData<Mandate>, credential: Credential,
 *         userInput: TransactionUserInput?, docRequestId: Int?,
 *     ): SdJwtTransactionResponse {
 *         val disclosure = transactionData.payload.disclosure  // base64url-encoded Disclosure
 *         val digest = Crypto.digest(Algorithm.SHA256, disclosure.encodeToByteArray()).toBase64Url()
 *         return SdJwtTransactionResponse(
 *             claims = mapOf(
 *                 // An array element replaced by its digest, as RFC 9901 §4.2.4.2 spells it.
 *                 "delegate_payload" to JsonArray(listOf(JsonObject(mapOf("..." to JsonPrimitive(digest))))),
 *                 "_sd_alg" to JsonPrimitive("sha-256"),
 *             ),
 *             disclosures = listOf(disclosure),
 *         )
 *     }
 * }
 *
 * documentTypeRepository.addTransactionType(MandateTransaction)
 * ```
 *
 * @param PayloadT type of the transaction-specific payload data.
 * @param displayName human-readable transaction name.
 * @param identifier unique transaction type identifier, corresponds to the `type` property in
 *  transaction data in OpenID4VP; all [TransactionType] objects must have distinct identifiers.
 * @param kbJwtResponseClaimName if transaction processing results in any data, it will be inserted
 *  in key binding JWT using this claim name; all [TransactionType] objects must have distinct values.
 *  Ignored when [nestSdJwtResponseClaims] is `false`, as the claims are then not nested under it.
 * @param iso18013RequestInfoIdentifier transaction type to use in `transactionData` map in
 *  `requestInfo` map in ISO/IEC 18013-5 document request to represent this transaction data;
 *  all [TransactionType] objects must have distinct values.
 * @param openId4VpMdocResponseNamespace namespace to use in `deviceSigned` namespace map in
 *  OpenID4VP response; defaults to [identifier].
 * @param nestSdJwtResponseClaims whether the claims returned by [generateSdJwtResponse] are
 *  nested in a JSON object under [kbJwtResponseClaimName] (the default). Set to `false` when a
 *  specification fixes the shape and position of its own KB-JWT claims; they are then merged in
 *  verbatim. Arrays set by several items of this type are concatenated in request order; any other
 *  value must be equal across items, and a claim that is one of the KB-JWT's own (`iat`, `aud`,
 *  `nonce`, `sd_hash`, `transaction_data_hashes`, `transaction_data_hashes_alg`) or that another
 *  type sets is refused.
 */
abstract class TransactionType<PayloadT: Any>(
    val displayName: String,
    val identifier: String,
    val kbJwtResponseClaimName: String = identifier,
    val iso18013RequestInfoIdentifier: String = identifier,
    val openId4VpMdocResponseNamespace: String = identifier,
    val nestSdJwtResponseClaims: Boolean = true,
) {
    /**
     * Returns the DeviceSigned namespace to use for the given presentment protocol.
     */
    fun getMdocResponseNamespace(protocol: TransactionProtocol): String = when (protocol) {
        TransactionProtocol.ISO_18013_5 -> ISO_18013_TRANSACTION_DATA_NAMESPACE
        TransactionProtocol.OPENID4VP -> openId4VpMdocResponseNamespace
    }

    /**
     * The `typ` header the Key Binding JWT carries when it signs [transactionData], all the items
     * of this type in the response, or `null` for the ordinary `kb+jwt`.
     *
     * Returns `null` by default. Override it when a specification gives the Key Binding JWT its own
     * `typ`, which may depend on what is being signed. One Key Binding JWT signs every item, so the
     * value is decided over all of them together and must not depend on their order.
     *
     * A type that returns disclosures from [generateSdJwtResponse] must override this, as a Key
     * Binding JWT that carries disclosures is never the ordinary `kb+jwt`; this cannot be checked
     * before the consent prompt, as the disclosures only exist once the response is generated.
     * The presentation is `<SD-JWT>~<KB-JWT>` when no type returns disclosures and the Delegated
     * SD-JWT form otherwise, whatever the `typ`: a `typ` without disclosures is a profile of its own.
     * Applies to OpenID4VP only: a presentation over ISO/IEC 18013-5 is refused if any type asks for
     * another `typ`.
     *
     * `null` means no preference, not `kb+jwt`: when types of one request disagree, the one that
     * returns a value wins over the ones that return `null`, and two different values are refused.
     */
    open fun sdJwtKbType(transactionData: List<TransactionData<PayloadT>>): String? = null

    /**
     * Checks the items of this type that one Key Binding JWT will sign together, before the consent
     * prompt, so a request that could only fail after the person approved is refused up front with
     * its reason. The items are all of this type for one credential, in request order.
     *
     * Does nothing by default. Override it for what no single item shows, such as two items that
     * cannot be answered in one response. Called for OpenID4VP requests only. The reason reaches the
     * caller as the cause of an [IllegalStateException] ("Problem processing transaction(s)").
     *
     * @param transactionData the items of this type for one credential.
     * @throws IllegalArgumentException if the items cannot be answered together.
     */
    @Throws(IllegalArgumentException::class)
    open fun validateTogether(transactionData: List<TransactionData<PayloadT>>) {}

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
     * @throws IllegalArgumentException if the transaction data is malformed.
     */
    @Throws(IllegalArgumentException::class)
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
     *
     * The payload comes from [parseOpenId4VpRequest]. The hash algorithms the verifier accepts
     * come from `transaction_data_hashes_alg`, so a type that does not override this function
     * still hashes its transaction data with the algorithm the verifier asked for.
     *
     * If `transaction_data_hashes_alg` is absent or `null`, [TransactionData.hashAlgorithms] is
     * left `null` and the default algorithm applies.
     *
     * @param serialized the base64url-encoded JSON transaction data item.
     * @return transaction data wrapping the parsed payload.
     * @throws IllegalArgumentException if the payload is malformed (see [parseOpenId4VpRequest]), or
     *  `transaction_data_hashes_alg` is present but not an array of strings, or names no known hash
     *  algorithm.
     */
    @Throws(IllegalArgumentException::class)
    open fun parseJson(serialized: ByteString): TransactionData<PayloadT> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        return TransactionData(
            type = this,
            payload = parseOpenId4VpRequest(jsonString),
            protocol = TransactionProtocol.OPENID4VP,
            rawBytes = serialized,
            hashAlgorithms = parseJoseHashAlgorithms(
                Json.parseToJsonElement(jsonString).jsonObject["transaction_data_hashes_alg"]
                    ?.takeIf { it != JsonNull }
                    ?.also {
                        require(it is JsonArray) {
                            "'transaction_data_hashes_alg' must be an array of strings: $it"
                        }
                    }
                    ?.jsonArray
                    ?.map { alg ->
                        require(alg is JsonPrimitive && alg.isString) {
                            "Hash algorithm in 'transaction_data_hashes_alg' must be a string: $alg"
                        }
                        alg.content
                    }
            ),
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
     * @throws IllegalArgumentException if the transaction data itself is malformed, which is not a
     *   property of [credential]; the message says why.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    open suspend fun isApplicable(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Boolean {
        if (credential !is SecureAreaBoundCredential) {
            return false
        }
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
     * Generates what this transaction adds to an SD-JWT presentation: the Key Binding JWT claims
     * and any disclosures that follow the Key Binding JWT.
     *
     * Returns the claims of [generateSdJwtResponseClaims] and no disclosures by default. A type
     * whose claims refer to disclosures it adds itself, such as a digest of each, overrides this
     * so the claims and the disclosures they commit to come from one call; it may build its claims
     * here and leave [generateSdJwtResponseClaims] alone.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @param userInput additional data specified by the user
     * @param docRequestId document request index in ISO 18013-5, null in OpenID4VP
     * @return the claims and the disclosures to add to the presentation; disclosures are supported
     *  over OpenID4VP only and require [sdJwtKbType] to be overridden
     * @throws CancellationException if the coroutine is cancelled
     */
    @Throws(CancellationException::class)
    open suspend fun generateSdJwtResponse(
        transactionData: TransactionData<PayloadT>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int? = null
    ): SdJwtTransactionResponse = SdJwtTransactionResponse(
        claims = generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId),
    )

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