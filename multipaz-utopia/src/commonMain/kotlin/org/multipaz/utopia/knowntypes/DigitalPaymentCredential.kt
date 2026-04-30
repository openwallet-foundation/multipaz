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
import org.multipaz.documenttype.DocumentAttributeSensitivity

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
                type = DocumentAttributeType.String,
                identifier = "issuer_name",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_ISSUER_NAME),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_ISSUER_NAME),
                mandatory = true,
                mdocNamespace = CARD_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = "Utopia Bank".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "payment_instrument_id",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_PAYMENT_INSTRUMENT_ID),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_PAYMENT_INSTRUMENT_ID),
                mandatory = false,
                mdocNamespace = CARD_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = "pi-77AABBCC".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "masked_account_reference",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_MASKED_ACCOUNT_REFERENCE),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_MASKED_ACCOUNT_REFERENCE),
                mandatory = true,
                mdocNamespace = CARD_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = "****1234".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "holder_name",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_HOLDER_NAME),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_HOLDER_NAME),
                mandatory = true,
                mdocNamespace = CARD_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "${SampleData.GIVEN_NAME} ${SampleData.FAMILY_NAME}".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issue_date",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_ISSUE_DATE),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_ISSUE_DATE),
                mandatory = true,
                mdocNamespace = CARD_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "expiry_date",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_ATTRIBUTE_EXPIRY_DATE),
                description = getLocalizedString(GeneratedStringKeys.PAYMENT_DESCRIPTION_EXPIRY_DATE),
                mandatory = true,
                mdocNamespace = CARD_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
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
