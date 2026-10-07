package org.multipaz.documenttype.knowntypes

import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.knowntypes.DrivingLicense.MDL_NAMESPACE
import org.multipaz.documenttype.PortraitEquivalenceRequest
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys

/**
 * Object containing Google Wallet ID pass metadata.
 *
 * See https://developers.google.com/wallet/identity/verify/supported-credential-attributes#id-pass-fields for
 * more information.
 */
object IDPass {
    const val IDPASS_DOCTYPE = "com.google.wallet.idcard.1"

    /**
     * Creates the ID Pass document type definition using localized display text.
     *
     * @param locale BCP-47 language tag used to resolve localized strings.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        val mDLNamespace = DrivingLicense.getDocumentType().mdocDocumentType!!.namespaces[MDL_NAMESPACE]!!
        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_GOOGLE_WALLET_ID_PASS))
            .addMdocDocumentType(IDPASS_DOCTYPE)
            .setSupportsPortraitImageEquivalence(MDL_NAMESPACE, "portrait")
            .addMdocNamespace(mDLNamespace)
            .addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_18),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_21",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_21),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_18_ZKP),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_21_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_21_ZKP),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_18_AND_PORTRAIT),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait_equivalence",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_18_AND_PORTRAIT_EQUIVALENCE),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    ),
                ),
                portraitEquivalenceRequest = PortraitEquivalenceRequest()
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait_or_portrait_equivalence",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_18_AND_PORTRAIT_OR_PORTRAIT_EQUIVALENCE),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    ),
                ),
                portraitEquivalenceRequest = PortraitEquivalenceRequest(
                    includePortrait = true
                )
            )
            .addSampleRequest(
                id = "age_over_21_and_portrait",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_AGE_OVER_21_AND_PORTRAIT),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_MANDATORY_DATA_ELEMENTS),
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
                displayName = getLocalizedString(GeneratedStringKeys.ID_PASS_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    MDL_NAMESPACE to mapOf(),
                )
            )
            .build()
    }
}
