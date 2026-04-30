package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.DocumentAttributeSensitivity

/**
 * Object containing the metadata of the Age Verification document type.
 * See https://ageverification.dev/ for more details about this document type.
 */
object AgeVerification {
    const val AV_DOCTYPE = "eu.europa.ec.av.1"
    const val AV_NAMESPACE = "eu.europa.ec.av.1"

    /**
     * Build the Age Verification Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return with(DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_AGE_VERIFICATION))) {
            addMdocDocumentType(AV_DOCTYPE)

            // Attribute age_over_NN.
            // If we provision all 99 age_over_NN claims the MSO will be 3886 bytes which exceeds the Longfellow-ZK
            // MSO size limit of ~ 2200 bytes. With these 13 claims, the MSO is 764 bytes which is more manageable.
            val ageThresholdsToProvision = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
            for (age in IntRange(1, 99)) {
                val ageString = age.toString()
                val placeholders = mapOf("age" to ageString)
                addMdocAttribute(
                    type = DocumentAttributeType.Boolean,
                    identifier = "age_over_${if (age < 10) "0$age" else "$age"}",
                    displayName = LocalizedStrings.getString(
                        GeneratedStringKeys.AGE_VERIFICATION_ATTRIBUTE_OLDER_THAN_YEARS,
                        locale,
                        placeholders
                    ),
                    description = LocalizedStrings.getString(
                        GeneratedStringKeys.AGE_VERIFICATION_DESCRIPTION_OLDER_THAN_YEARS,
                        locale,
                        placeholders
                    ),
                    mandatory = (age == 18),
                    mdocNamespace = AV_NAMESPACE,
                    sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                    icon = Icon.TODAY,
                    sampleValue =
                        if (age in ageThresholdsToProvision) {
                            (SampleData.AGE_IN_YEARS >= age).toDataItem()
                        } else {
                            null
                        }
                )
            }
            // Sample requests.
            addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.AGE_VERIFICATION_REQUEST_AGE_OVER_18),
                mdocDataElements = mapOf(
                    AV_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                )
            )
            addSampleRequest(
                id = "age_over_18_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.AGE_VERIFICATION_REQUEST_AGE_OVER_18_ZKP),
                mdocDataElements = mapOf(
                    AV_NAMESPACE to mapOf(
                        "age_over_18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            addSampleRequest(
                id = "age_over_21",
                displayName = getLocalizedString(GeneratedStringKeys.AGE_VERIFICATION_REQUEST_AGE_OVER_21),
                mdocDataElements = mapOf(
                    AV_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                )
            )
            addSampleRequest(
                id = "age_over_21_zkp",
                displayName = getLocalizedString(GeneratedStringKeys.AGE_VERIFICATION_REQUEST_AGE_OVER_21_ZKP),
                mdocDataElements = mapOf(
                    AV_NAMESPACE to mapOf(
                        "age_over_21" to false,
                    )
                ),
                mdocUseZkp = true
            )
            addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.AGE_VERIFICATION_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    AV_NAMESPACE to mapOf()
                )
            )
            build()
        }
    }
}
