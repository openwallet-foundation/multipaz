package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.datetime.LocalDate

/**
 * Example Payment SCA credential profile for card-based digital payments.
 *
 * This document type is a mock credential used for testing in a closed ecosystem and is
 * non-normative. Field names, semantics, and sample values are subject to change.
 */
object DigitalPaymentCredential {
    const val CARD_DOCTYPE = "org.multipaz.payment.sca.1"
    const val CARD_NAMESPACE = "org.multipaz.payment.sca.1"

    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Payment card")
            .addMdocDocumentType(CARD_DOCTYPE)

            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuer_name",
                "Issuer name",
                "Human-readable issuer name.",
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "Utopia Bank".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "payment_instrument_id",
                "Payment instrument ID",
                "Tokenized payment instrument identifier.",
                false,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "pi-77AABBCC".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "masked_account_reference",
                "Masked account reference",
                "Masked account reference, for example PAN last 4.",
                true,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "****1234".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "holder_name",
                "Holder name",
                "Payment account holder name.",
                true,
                CARD_NAMESPACE,
                Icon.PERSON,
                "${SampleData.GIVEN_NAME} ${SampleData.FAMILY_NAME}".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                "Issue date",
                "Date when this credential was issued.",
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                "Expiry date",
                "Date when this credential expires.",
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )

            .addSampleRequest(
                id = "payment_sca_minimal",
                displayName = "Payment SCA (minimal)",
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf(
                        "issuer_name" to false,
                        "payment_instrument_id" to false,
                        "masked_account_reference" to false,
                        "holder_name" to false,
                        "issue_date" to false,
                        "expiry_date" to false
                    )
                )
            )
            .addSampleRequest(
                id = "payment_sca_full",
                displayName = "Payment SCA (all data elements)",
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf()
                )
            )
            .build()
    }
}