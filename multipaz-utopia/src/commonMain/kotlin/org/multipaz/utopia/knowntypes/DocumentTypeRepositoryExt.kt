package org.multipaz.utopia.knowntypes

import kotlinx.io.bytestring.ByteString
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
 * [DelegateTransaction] is not added: an application that registers it must also register a
 * renderer that shows the mandate in the consent prompt, so it adds both itself, with
 * [addDelegateTransactionType].
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

/**
 * Adds [DelegateTransaction], which [addUtopiaTypes] leaves out.
 *
 * An application adds it together with a renderer that shows the mandates in the consent prompt,
 * such as the SDK's default one, `DelegateTransactionRendererCompose` or
 * `DelegateTransactionRendererSwiftUi`.
 *
 * This is the same as `addTransactionType(DelegateTransaction)`, which Swift can't call: there the
 * parameter is a `TransactionType<AnyObject>`, and `DelegateTransaction` is not one.
 *
 * @receiver the [DocumentTypeRepository] to add to.
 */
fun DocumentTypeRepository.addDelegateTransactionType() {
    addTransactionType(DelegateTransaction)
}

private fun createEUPersonalIDWithTransactionCannedRequest(
    eupidDocumentType: DocumentType
) = SingleDocumentCannedRequest(
    id = "withTransaction",
    displayName = "EU PID with Ping Transaction",
    mdocRequest = MdocCannedRequest(
        docType = eupidDocumentType.mdocDocumentType!!.docType,
        useZkp = false,
        portraitEquivalenceRequest = null,
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
            payload = PingTransaction.Payload(
                string = "string data",
                blob = ByteString(byteArrayOf(1, 2, 3))
            )
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