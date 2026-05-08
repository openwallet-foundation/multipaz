package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.utopia.localization.GeneratedStringKeys
import org.multipaz.utopia.localization.LocalizedStrings

/**
 * EMVCo Digital Payment Credential (DPC) profile for SD-JWT digital payments.
 *
 * Compliant with the EMVCo DPC Card Credential JSON Schema (urn:emvco:dpc:card:1).
 */
object DigitalPaymentCredentialSdJwt {
    const val CARD_VCT = "urn:emvco:dpc:card:1"

    /**
     * Creates the Digital Payment Credential SD-JWT document type definition with localized labels.
     *
     * @param locale BCP-47 language tag used to resolve localized strings.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_PAYMENT_CARD))
            .addJsonDocumentType(type = CARD_VCT, keyBound = true)
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "credential_id",
                displayName = "Credential ID",
                description = "Unique identifier of this specific credential instance.",
                icon = Icon.NUMBERS,
                sampleValue = JsonPrimitive("urn:uuid:9f2b7a2e-3b74-4a0d-9b1a-0e6a91f5d2c8")
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "network",
                displayName = "Card Network",
                description = "Payment network or scheme associated with the credential.",
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = JsonPrimitive("multipaz")
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "last_four",
                displayName = "Last Four Digits",
                description = "The last four digits of the PAN for display and recognition.",
                icon = Icon.NUMBERS,
                sampleValue = JsonPrimitive("1513")
            )
            .addSampleRequest(
                id = "payment_sca_minimal",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_MINIMAL),
                jsonClaims = listOf(
                    "credential_id",
                    "network",
                    "last_four"
                )
            )
            .addSampleRequest(
                id = "payment_sca_full",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_ALL),
                jsonClaims = listOf()
            )
            .addSampleRequest(
                id = "payment_transaction",
                displayName = getLocalizedString(GeneratedStringKeys.PAYMENT_REQUEST_PAYMENT_SCA_ALL),
                jsonClaims = listOf(),
                cannedTransactionData = listOf(PaymentTransaction.sampleData)
            )
            .build()
    }
}
