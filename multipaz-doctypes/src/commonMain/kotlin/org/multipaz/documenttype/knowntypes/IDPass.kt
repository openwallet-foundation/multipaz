package org.multipaz.documenttype.knowntypes

import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.knowntypes.DrivingLicense.MDL_NAMESPACE

/**
 * Object containing Google Wallet ID pass metadata.
 *
 * See https://developers.google.com/wallet/identity/verify/supported-credential-attributes#id-pass-fields for
 * more information.
 */
object IDPass {
    const val IDPASS_DOCTYPE = "com.google.wallet.idcard.1"

    fun getDocumentType(): DocumentType {
        val mDLNamespace = DrivingLicense.getDocumentType().mdocDocumentType!!.namespaces[MDL_NAMESPACE]!!
        return DocumentType.Builder("Google Wallet ID pass")
            .addMdocDocumentType(IDPASS_DOCTYPE)
            .addMdocNamespace(mDLNamespace)
            .addSampleRequest(
                id = "age_over_18",
                displayName ="Age over 18",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21",
                displayName ="Age over 21",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName ="Age over 18 (ZKP)",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_21_zkp",
                displayName ="Age over 21 (ZKP)",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName ="Age over 18 + portrait",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21_and_portrait",
                displayName ="Age over 21 + portrait",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = "Mandatory data elements",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "birth_date" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                        "issuing_country" to false,
                        "issuing_authority" to false,
                        "document_number" to false,
                        "portrait" to false,
                        "driving_privileges" to false,
                        "un_distinguishing_sign" to false,
                    )
                )
            )
            .addSampleRequest(
                id = "full",
                displayName ="All data elements",
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(),
                )
            )
            .build()
    }
}