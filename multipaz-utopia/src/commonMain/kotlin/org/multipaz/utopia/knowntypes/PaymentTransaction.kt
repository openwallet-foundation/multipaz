package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.putCborMap
import org.multipaz.credential.Credential
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.MdocDataElement
import org.multipaz.documenttype.StringOption
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.TransactionData

/**
 * Payment transaction as defined by
 * [Specification of Strong Customer Authentication (SCA) Implementation with the Wallet](https://github.com/eu-digital-identity-wallet/eudi-doc-standards-and-technical-specifications/blob/main/docs/technical-specifications/ts12-electronic-payments-SCA-implementation-with-wallet.md)
 */
object PaymentTransaction: TransactionType(
    displayName = "Payment",
    identifier = "urn:eudi:sca:payment:1",
    attributes = listOf(
        MdocDataElement(
            mandatory = true,
            attribute = DocumentAttribute(
                identifier = "payload",
                type = DocumentAttributeType.ComplexType,
                displayName = "Transaction data",
                description = "Transaction data",
                embeddedAttributes = listOf(
                    DocumentAttribute(
                        identifier = "transaction_id",
                        type = DocumentAttributeType.String,
                        displayName = "Transaction identifier",
                        description = "Unique identifier of the Relying Party's interaction with the User"
                    ),
                    DocumentAttribute(
                        identifier = "date_time",
                        type = DocumentAttributeType.DateTime,
                        displayName = "Payment date",
                        description = "Date and time when the Relying Party started to interact with the User"
                    ),
                    DocumentAttribute(
                        identifier = "payee",
                        type = DocumentAttributeType.ComplexType,
                        displayName = "Payee",
                        description = "Payee details",
                        embeddedAttributes = listOf(
                            DocumentAttribute(
                                identifier = "name",
                                type = DocumentAttributeType.String,
                                displayName = "Name",
                                description = "Name of the Payee to whom the payment is being made"
                            ),
                            DocumentAttribute(
                                identifier = "id",
                                type = DocumentAttributeType.String,
                                displayName = "Id",
                                description = "An identifier of the Payee that is understood by the payment system used to process the transaction"
                            ),
                            DocumentAttribute(
                                identifier = "logo",
                                type = DocumentAttributeType.String,
                                displayName = "Logo",
                                description = "Resolvable or Data (as per [RFC2397]) URL of the Payee logo"
                            ),
                            DocumentAttribute(
                                identifier = "website",
                                type = DocumentAttributeType.String,
                                displayName = "Website",
                                description = "Resolvable URL of the Payee's website"
                            ),
                        )
                    ),
                    DocumentAttribute(
                        identifier = "pisp",
                        type = DocumentAttributeType.ComplexType,
                        displayName = "PISP",
                        description = "If present, it indicates that the payment is being facilitated by a PISP",
                        embeddedAttributes = listOf(
                            DocumentAttribute(
                                identifier = "legal_name",
                                type = DocumentAttributeType.String,
                                displayName = "Legal name",
                                description = "Legal name of the PISP"
                            ),
                            DocumentAttribute(
                                identifier = "brand_name",
                                type = DocumentAttributeType.String,
                                displayName = "Brand name",
                                description = "Brand name of the PISP"
                            ),
                            DocumentAttribute(
                                identifier = "domain_name",
                                type = DocumentAttributeType.String,
                                displayName = "Domain name",
                                description = "Domain name of the PISP as secured by the [eIDAS] QWAC certificate of the TPP"
                            ),
                        )
                    ),
                    DocumentAttribute(
                        identifier = "execution_date",
                        type = DocumentAttributeType.Date,
                        displayName = "Execution date",
                        description = "[ISO8601] date of the payment's execution"
                    ),
                    DocumentAttribute(
                        identifier = "currency",
                        type = DocumentAttributeType.String,
                        displayName = "Currency",
                        description = "Currency of the payment(s) as [ISO4217] Alpha-3 code"
                    ),
                    DocumentAttribute(
                        identifier = "amount",
                        type = DocumentAttributeType.Number,
                        displayName = "Amount",
                        description = "Amount of the single payment, or regular amount of a recurring payment, consisting of major currency units as the integer component and an optional fraction part consisting of the decimal point followed by minor currency units, with the number of fractional digits as per [ISO4217]"
                    ),
                    DocumentAttribute(
                        identifier = "amount_estimated",
                        type = DocumentAttributeType.Boolean,
                        displayName = "Is estimated",
                        description = "In case of an MIT, indicates that the amount is estimated. Absence of this optional attribute indicates that the amount is not estimated"
                    ),
                    DocumentAttribute(
                        identifier = "amount_earmarked",
                        type = DocumentAttributeType.Boolean,
                        displayName = "Is earmarked",
                        description = "In case of an MIT that is not executed immediately, indicates that the Payee earmarks the amount immediately. Absence of this optional attribute indicates that no earmarking is taking place"
                    ),
                    DocumentAttribute(
                        identifier = "sct_inst",
                        type = DocumentAttributeType.Boolean,
                        displayName = "Is Instant Credit Transfer",
                        description = "Indicates that the ASPSP is requested to execute the payment as SEPA Instant Credit Transfer. Absence of this optional attribute indicates that no instant transfer has been requested"
                    ),
                    DocumentAttribute(
                        identifier = "recurrence",
                        type = DocumentAttributeType.ComplexType,
                        displayName = "Recurrence",
                        description = "If present, it indicates a recurring payment",
                        embeddedAttributes = listOf(
                            DocumentAttribute(
                                identifier = "start_date",
                                type = DocumentAttributeType.Date,
                                displayName = "Start date",
                                description = "[ISO8601] date of the first payment's execution. This attribute is expected to be present in most cases, with the only exception of MITs when the date of the first payment due is unknown at the time of SCA"
                            ),
                            DocumentAttribute(
                                identifier = "end_date",
                                type = DocumentAttributeType.Date,
                                displayName = "End date",
                                description = "[ISO8601] date of the last payment's execution"
                            ),
                            DocumentAttribute(
                                identifier = "number",
                                type = DocumentAttributeType.Number,
                                displayName = "Number",
                                description = "Number of recurring payments"
                            ),
                            DocumentAttribute(
                                identifier = "frequency",
                                type = DocumentAttributeType.StringOptions(listOf(
                                    StringOption("INDA", "intraday (i.e., several times a day)"),
                                    StringOption("DAIL", "daily"),
                                    StringOption("WEEK", "weekly"),
                                    StringOption("TOWK", "bi-weekly"),
                                    StringOption("TWMN", "twice a month"),
                                    StringOption("MNTH", "monthly"),
                                    StringOption("TOMN", "every two months"),
                                    StringOption("QUTR", "quarterly"),
                                    StringOption("FOMN", "every four months"),
                                    StringOption("SEMI", "twice a year"),
                                    StringOption("YEAR", "yearly"),
                                    StringOption("TYEA", "every two years"),
                                )),
                                displayName = "Frequency",
                                description = "Frequency of recurring payments"
                            ),
                            DocumentAttribute(
                                identifier = "mit_options",
                                type = DocumentAttributeType.ComplexType,
                                displayName = "MIT options",
                                description = "Recurring MITs options",
                                embeddedAttributes = listOf(
                                    DocumentAttribute(
                                        identifier = "amount_variable",
                                        type = DocumentAttributeType.Boolean,
                                        displayName = "Amount is variable",
                                        description = "Indicates if subsequent transactions may have a different amount compared to the first transaction. Absence of this optional attribute indicates that the amount does not vary"
                                    ),
                                    DocumentAttribute(
                                        identifier = "min_amount",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Minimum amount",
                                        description = "The minimum amount of a single payment under this transaction"
                                    ),
                                    DocumentAttribute(
                                        identifier = "max_amount",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Maximum amount",
                                        description = "The maximum amount of a single payment under this transaction"
                                    ),
                                    DocumentAttribute(
                                        identifier = "total_amount",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Total amount",
                                        description = "The total amount of all payments under this transaction"
                                    ),
                                    DocumentAttribute(
                                        identifier = "initial_amount",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Initial amount",
                                        description = "The deviating amount for a fixed number of initial instances of the recurring payment"
                                    ),
                                    DocumentAttribute(
                                        identifier = "initial_amount_number",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Number of initial amount payments",
                                        description = "The number of initial instances of the recurring payment with a deviating amount"
                                    ),
                                    DocumentAttribute(
                                        identifier = "apr",
                                        type = DocumentAttributeType.Number,
                                        displayName = "Annual Percentage Rate",
                                        description = "Annual Percentage Rate of the installment. Presence of this attribute indicates that the transaction is an interest-bearing installment"
                                    ),
                                )
                            )
                        )
                    ),
                )
            )
        )
    )
) {
    override suspend fun isApplicable(
        transactionData: TransactionData,
        credential: Credential
    ): Boolean {
        return credential is MdocCredential
                && credential.docType == DigitalPaymentCredential.CARD_DOCTYPE
    }

    override suspend fun applyCbor(
        transactionData: TransactionData,
        credential: Credential
    ): Map<String, DataItem> {
        return buildMap {}
    }

    /** Sample transaction data for this transaction type */
    val sampleData = buildCanned {
        putCborMap("payload") {
            put("transaction_id", "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9")
            put("amount", 123.25)
            put("currency", "USD")
            putCborMap("payee") {
                put("id", "01234")
                put("name", "Linux Foundation")
            }
        }
    }
}