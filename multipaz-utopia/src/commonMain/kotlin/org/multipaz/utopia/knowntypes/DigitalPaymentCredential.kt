package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.datetime.LocalDate
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.SampleData

/**
 * Example Payment SCA credential profile for card-based digital payments.
 *
 * This document type is a mock credential used for testing in a closed ecosystem and is
 * non-normative. Field names, semantics, and sample values are subject to change.
 */
object DigitalPaymentCredential {
    const val CARD_DOCTYPE = "org.multipaz.payment.sca.1"
    const val CARD_NAMESPACE = "org.multipaz.payment.sca.1"

    /**
     * Creates the Digital Payment Credential document type definition with localized labels.
     *
     * @param locale BCP-47 language tag used to resolve localized strings.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_PAYMENT_CARD))
            .addMdocDocumentType(CARD_DOCTYPE)

            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuer_name",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_ISSUER_NAME),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_ISSUER_NAME),
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "Utopia Bank".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "payment_instrument_id",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_PAYMENT_INSTRUMENT_ID),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_PAYMENT_INSTRUMENT_ID),
                false,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "pi-77AABBCC".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "masked_account_reference",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_MASKED_ACCOUNT_REFERENCE),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_MASKED_ACCOUNT_REFERENCE),
                true,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "****1234".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "holder_name",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_HOLDER_NAME),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_HOLDER_NAME),
                true,
                CARD_NAMESPACE,
                Icon.PERSON,
                "${SampleData.GIVEN_NAME} ${SampleData.FAMILY_NAME}".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_ISSUE_DATE),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_ISSUE_DATE),
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_EXPIRY_DATE),
                getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_EXPIRY_DATE),
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )

            .addSampleRequest(
                id = "payment_sca_minimal",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_MINIMAL),
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
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_ALL),
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf()
                )
            )
            .addSampleRequest(
                id = "payment_transaction",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_ALL),
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf()
                ),
                cannedTransactionData = listOf(PaymentTransaction.sampleData)
            )
            .build()
    }
}
