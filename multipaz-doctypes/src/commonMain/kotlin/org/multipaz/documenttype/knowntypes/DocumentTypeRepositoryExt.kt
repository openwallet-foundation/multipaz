package org.multipaz.documenttype.knowntypes

import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.documenttype.DocumentTypeRepository

/**
 * Adds all known document and transaction data types from the `multipaz-doctypes` library.
 *
 * @receiver the [DocumentTypeRepository] to add to.
 * @param locale BCP-47 language tag used to resolve localized strings.
 */
fun DocumentTypeRepository.addKnownTypes(locale: String = LocalizedStrings.getCurrentLocale()) {
    addDocumentType(Aadhaar.getDocumentType( /* TODO: use locale */ ))
    addDocumentType(AgeVerification.getDocumentType(locale))
    addDocumentType(DrivingLicense.getDocumentType(locale))
    addDocumentType(EUPersonalID.getDocumentType(locale))
    addDocumentType(IDPass.getDocumentType(locale))
    addDocumentType(PhotoID.getDocumentType(locale))
    addDocumentType(VaccinationDocument.getDocumentType(locale))
    addDocumentType(VehicleRegistration.getDocumentType(locale))
}
