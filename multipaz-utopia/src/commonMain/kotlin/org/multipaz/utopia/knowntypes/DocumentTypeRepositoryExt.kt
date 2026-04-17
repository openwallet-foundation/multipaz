package org.multipaz.utopia.knowntypes

import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.documenttype.DocumentTypeRepository

/**
 * Adds all known document and transaction data types from the `multipaz-utopia` library..
 *
 * @receiver the [DocumentTypeRepository] to add to.
 * @param locale BCP-47 language tag used to resolve localized strings.
 */
fun DocumentTypeRepository.addUtopiaTypes(locale: String = LocalizedStrings.getCurrentLocale()) {
    addDocumentType(DigitalPaymentCredential.getDocumentType(locale))
    addDocumentType(EUCertificateOfResidence.getDocumentType(locale))
    addDocumentType(GermanPersonalID.getDocumentType(locale))
    addDocumentType(Loyalty.getDocumentType(locale))
    addDocumentType(UtopiaBoardingPass.getDocumentType(locale))
    addDocumentType(UtopiaMovieTicket.getDocumentType(locale))
    addDocumentType(UtopiaNaturalization.getDocumentType(locale))
    addTransactionType(PingTransaction)
    addTransactionType(PaymentTransaction)
}
