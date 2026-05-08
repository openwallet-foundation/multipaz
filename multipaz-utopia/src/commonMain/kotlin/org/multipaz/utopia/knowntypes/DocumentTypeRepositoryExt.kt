package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentType
import org.multipaz.utopia.localization.LocalizedStrings
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.JsonCannedRequest
import org.multipaz.documenttype.JsonDocumentType
import org.multipaz.documenttype.MdocCannedRequest
import org.multipaz.documenttype.MdocDocumentType
import org.multipaz.documenttype.MdocNamespaceRequest
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PaymentTransaction

/**
 * Adds all known document and transaction data types from the `multipaz-utopia` library..
 *
 * @receiver the [DocumentTypeRepository] to add to.
 * @param locale BCP-47 language tag used to resolve localized strings.
 */
fun DocumentTypeRepository.addUtopiaTypes(locale: String = LocalizedStrings.getCurrentLocale()) {
    addDocumentType(DigitalPaymentCredential.getDocumentType(locale))
    addDocumentType(DigitalPaymentCredentialSdJwt.getDocumentType(locale))
    addDocumentType(EUCertificateOfResidence.getDocumentType(locale))
    addDocumentType(GermanPersonalID.getDocumentType(locale))
    addDocumentType(Loyalty.getDocumentType(locale))
    addDocumentType(UtopiaBoardingPass.getDocumentType(locale))
    addDocumentType(UtopiaMovieTicket.getDocumentType(locale))
    addDocumentType(UtopiaNaturalization.getDocumentType(locale))
    addTransactionType(PingTransaction)
    addTransactionType(PaymentTransaction)
    addExtraSingleDocumentCannedRequest(
        createEUPersonalIDWithTransactionCannedRequest(
            getDocumentTypeForMdoc(EUPersonalID.EUPID_NAMESPACE)!!
        )
    )
}

private fun createEUPersonalIDWithTransactionCannedRequest(
    eupidDocumentType: DocumentType
) = SingleDocumentCannedRequest(
    id = "withTransaction",
    displayName = "With Transaction Data",
    mdocRequest = MdocCannedRequest(
        docType = eupidDocumentType.mdocDocumentType!!.docType,
        useZkp = false,
        namespacesToRequest = listOf(
            makeMdocNamespaceRequest(
                mdocDocumentType = eupidDocumentType.mdocDocumentType!!,
                namespace = EUPersonalID.EUPID_NAMESPACE,
                dataElementsToRequest = mapOf(
                    "family_name" to false,
                    "given_name" to false,
                    "birth_date" to false,
                )
            )
        )
    ),
    jsonRequest = JsonCannedRequest(
        vct = eupidDocumentType.jsonDocumentType!!.vct,
        claimsToRequest = makeClaimsToRequest(
            jsonDocumentType = eupidDocumentType.jsonDocumentType!!,
            claimsToRequest = listOf(
                "family_name",
                "given_name",
                "birthdate"
            )
        )
    ),
    transactionData = listOf(
        CannedTransactionData(
            transactionType = PingTransaction,
            attributes = buildCborMap {
                put("string", "string data")
                put("blob", byteArrayOf(1, 2, 3).toDataItem())
            } as CborMap
        )
    )
)

private fun makeMdocNamespaceRequest(
    mdocDocumentType: MdocDocumentType,
    namespace: String,
    dataElementsToRequest: Map<String, Boolean>
): MdocNamespaceRequest {
    val mdocNamespace = mdocDocumentType.namespaces[namespace]!!
    return MdocNamespaceRequest(
        namespace = namespace,
        dataElementsToRequest = dataElementsToRequest.mapKeys { (name, _) ->
            mdocNamespace.dataElements[name]!!
        }
    )
}

private fun makeClaimsToRequest(
    jsonDocumentType: JsonDocumentType,
    claimsToRequest: List<String>
): List<DocumentAttribute> = claimsToRequest.map {
    jsonDocumentType.claims[it]!!
}