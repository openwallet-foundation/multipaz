package org.multipaz.documenttype.knowntypes

import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.TransactionData
import org.multipaz.util.fromBase64Url
import kotlin.time.Instant

/**
 * Payment transaction as defined by
 * [Specification of Strong Customer Authentication (SCA) Implementation with the Wallet](https://github.com/eu-digital-identity-wallet/eudi-doc-standards-and-technical-specifications/blob/main/docs/technical-specifications/ts12-electronic-payments-SCA-implementation-with-wallet.md)
 */
object PaymentTransaction: TransactionType<PaymentTransaction.Payload>(
    displayName = "Payment",
    identifier = "urn:eudi:sca:payment:1"
) {
    /**
     * Represents the wrapper envelope for a JSON-serialized payment transaction.
     *
     * This class encapsulates metadata such as credential IDs used for authorization,
     * a list of cryptographic hash algorithms applied to the transaction data, and the
     * core transaction [payload].
     *
     * @property type The identifier of the payload or credential type.
     * @property credentialIds A list of unique identifiers for authorized credentials.
     * @property transactionDataHashesAlg An optional list of cryptographic hash algorithms used
     * to secure the transaction data.
     * @property payload The core [Payload] containing transaction-specific details.
     */
    @Serializable
    data class JsonData(
        val type: String,
        val credentialIds: List<String>,
        val transactionDataHashesAlg: List<String>?,
        val payload: Payload
    )

    /**
     * Represents the wrapper envelope for a CBOR-serialized payment transaction.
     *
     * This serves as a binary alternative to [JsonData], optimizing performance
     * and payload size by mapping algorithm identifiers to numeric values.
     *
     * @property transactionDataHashesAlg An optional list of cryptographic hash algorithms
     * represented as CBOR integer identifiers (`List<Long>`).
     * @property payload The core [Payload] containing transaction-specific details.
     */
    @CborSerializable
    data class CborData(
        val transactionDataHashesAlg: List<Long>?,
        val payload: Payload
    ) {
        companion object
    }

    /**
     * The core detail schema of a payment transaction.
     *
     * In the target JSON schema, these properties map to `snake_case` (e.g., `transactionId`
     * maps to `transaction_id`).
     *
     * @property transactionId Unique identifier for the transaction (mapped to `transaction_id`).
     * Must be between 1 and 36 characters (typically a UUID). **Required.**
     * @property currency ISO 4217 3-letter currency code matching the pattern `^[A-Z]{3}$`. **Required.**
     * @property amount The numeric value of the transaction. **Required.**
     * @property payee Details of the receiving entity ([Payee]). **Required.**
     * @property dateTime The exact point in time when the transaction was created (mapped to `date_time`).
     *  Expected in RFC 3339 date-time format (e.g., "2025-11-13T20:20:39+00:00").
     * @property pisp The Payment Initiation Service Provider ([Pisp]) assisting in the transaction, if applicable.
     * @property executionDate The planned execution date (mapped to `execution_date`).
     *  Mapped to `date-time` format in the JSON schema.
     * @property amountEstimated Flag indicating if the amount is an estimate rather than the final total (mapped to `amount_estimated`).
     * @property amountEarmarked Flag indicating if the funds are reserved/earmarked for this transaction (mapped to `amount_earmarked`).
     * @property sctInst Flag indicating if the transaction uses SEPA Instant Credit Transfer (mapped to `sct_inst`).
     * @property recurrence Optional [Recurrence] configuration specifying payment intervals for standing/recurring orders.
     * @property mitOptions Optional Merchant Initiated Transaction options ([MitOptions]).
     */
    @CborSerializable
    @Serializable
    data class Payload(
        val transactionId: String,
        val currency: String,
        val amount: Double,
        val payee: Payee,
        val dateTime: Instant? = null,
        val pisp: Pisp? = null,
        val executionDate: LocalDate? = null,
        val amountEstimated: Boolean? = null,
        val amountEarmarked: Boolean? = null,
        val sctInst: Boolean? = null,
        val recurrence: Recurrence? = null,
        val mitOptions: MitOptions? = null
    ) {
        companion object
    }

    /**
     * Represents the merchant or individual receiving the payment.
     *
     * @property name The display name or legal name of the receiving merchant/person. **Required.**
     * @property id Unique identifier of the payee. **Required.**
     * @property logo An optional URI string pointing to the payee's brand logo asset.
     * @property website An optional URI string linking to the payee's official website.
     */
    @CborSerializable
    @Serializable
    data class Payee(
        val name: String,
        val id: String,
        val logo: String? = null,
        val website: String? = null
    ) {
        companion object
    }

    /**
     * Details of the Payment Initiation Service Provider (PISP) executing the payment request.
     *
     * @property legalName The official registered company name of the provider (mapped to `legal_name`). **Required.**
     * @property brandName The commercial customer-facing brand name of the provider (mapped to `brand_name`). **Required.**
     * @property domainName The verified domain name associated with the provider (mapped to `domain_name`). **Required.**
     */
    @CborSerializable
    @Serializable
    data class Pisp(
        val legalName: String,
        val brandName: String,
        val domainName: String
    ) {
        companion object
    }

    /**
     * Configures the timeline and interval parameters of a recurring payment sequence.
     *
     * @property frequency How often the payment repeats ([Frequency]). **Required.**
     * @property startDate The date when the payment series officially begins (mapped to `start_date`).
     * @property endDate The final date of the payment series (mapped to `end_date`).
     * @property number The total occurrences planned for this recurring agreement.
     */
    @CborSerializable
    @Serializable
    data class Recurrence(
        val frequency: Frequency,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
        val number: Long? = null,
    ) {
        companion object
    }

    /**
     * Merchant-Initiated Transaction (MIT) configurations for variable or conditional recurring payments.
     *
     * @property amountVariable True if payment amounts fluctuate based on actual usage/billing (mapped to `amount_variable`).
     * @property minAmount The absolute minimum amount allowed per cycle (mapped to `min_amount`).
     * @property maxAmount The absolute maximum amount allowed per cycle (mapped to `max_amount`).
     * @property totalAmount The maximum lifetime cap for all payments combined under this agreement (mapped to `total_amount`).
     * @property initialAmount The specific cost of the very first transaction in the cycle (mapped to `initial_amount`).
     * @property initialAmountNumber The sequence index or identifier for the initial pricing tier (mapped to `initial_amount_number`).
     * @property apr Annual Percentage Rate (APR) applied if this recurring transaction acts as a credit/finance agreement.
     */
    @CborSerializable
    @Serializable
    data class MitOptions(
        val amountVariable: Boolean?,
        val minAmount: Double?,
        val maxAmount: Double?,
        val totalAmount: Double?,
        val initialAmount: Double?,
        val initialAmountNumber: Long?,
        val apr: Double?
    ) {
        companion object
    }

    /**
     * Standard banking frequency codes used to define how often a recurring payment occurs.
     */
    enum class Frequency {
        /** Intraday (i.e., several times a day). */
        INDA,
        /** Daily. */
        DAIL,
        /** Weekly. */
        WEEK,
        /** Bi-weekly. */
        TOWK,
        /** Twice a month. */
        TWMN,
        /** Monthly. */
        MNTH,
        /** Every two months. */
        TOMN,
        /** Quarterly. */
        QUTR,
        /** Every four months. */
        FOMN,
        /** Twice a year. */
        SEMI,
        /** Yearly. */
        YEAR,
        /** Every two years. */
        TYEA
    }

    override fun serializeCbor(
        payload: Payload,
        hashAlgorithms: List<Algorithm>?
    ): ByteString = ByteString(
        CborData(
            transactionDataHashesAlg = coseHashAlgorithms(hashAlgorithms),
            payload = payload
        ).toCbor()
    )

    override fun serializeJson(
        payload: Payload,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>?
    ): String =
        jsonFormat.encodeToString(JsonData(
            type = identifier,
            transactionDataHashesAlg = joseHashAlgorithms(hashAlgorithms),
            credentialIds = credentialIds,
            payload = payload
        ))

    override fun parseJson(serialized: ByteString): TransactionData<Payload> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        return TransactionData(
            type = this,
            serialized = serialized,
            hashAlgorithms = parseJoseHashAlgorithms(data.transactionDataHashesAlg),
            payload = data.payload,
        )
    }

    override fun parseCbor(serialized: ByteString): TransactionData<Payload> {
        val data = CborData.fromCbor(serialized.toByteArray())
        return TransactionData(
            type = this,
            serialized = serialized,
            hashAlgorithms = parseCoseHashAlgorithms(data.transactionDataHashesAlg),
            payload = data.payload,
        )
    }

    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        return credential is MdocCredential
                && credential.docType == "org.multipaz.payment.sca.1"
    }

    override suspend fun applyCbor(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Map<String, DataItem> {
        return buildMap {}
    }

    /** Sample transaction data for this transaction type */
    val sampleData = CannedTransactionData<Payload>(
        transactionType = PaymentTransaction,
        payload = Payload(
            transactionId = "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
            amount = 123.25,
            currency = "USD",
            payee = Payee(
                id = "01234",
                name = "Linux Foundation"
            )
        )
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonFormat = Json {
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
}