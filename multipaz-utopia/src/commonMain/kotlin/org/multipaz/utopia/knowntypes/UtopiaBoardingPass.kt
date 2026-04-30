package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import kotlin.time.Clock

/**
 * An example of what a boarding pass doctype could look like.
 *
 */
object UtopiaBoardingPass {
    const val BOARDING_PASS_DOCTYPE = "org.multipaz.example.boarding-pass.1"
    const val BOARDING_PASS_NS = "org.multipaz.example.boarding-pass.1"

    /**
     * Creates the Boarding Pass document type definition using localized strings.
     *
     * @param locale BCP-47 language tag used to resolve localized strings.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_BOARDING_PASS)).apply {
            addMdocDocumentType(BOARDING_PASS_DOCTYPE)
            addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "passenger_name",
                displayName = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_PASSENGER_NAME),
                description = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_PASSENGER_NAME),
                mandatory = true,
                mdocNamespace = BOARDING_PASS_NS,
                icon = Icon.PERSON,
                sampleValue = "Erika Mustermann".toDataItem()
            )
            addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "flight_number",
                displayName = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_FLIGHT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_FLIGHT_NUMBER),
                mandatory = true,
                mdocNamespace = BOARDING_PASS_NS,
                icon = Icon.AIRPORT_SHUTTLE,
                sampleValue = "United 815".toDataItem()
            )
            addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "seat_number",
                displayName = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_SEAT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_SEAT_NUMBER),
                mandatory = true,
                mdocNamespace = BOARDING_PASS_NS,
                icon = Icon.DIRECTIONS,
                sampleValue = "12A".toDataItem()
            )
            addMdocAttribute(
                type = DocumentAttributeType.DateTime,
                identifier = "departure_time",
                displayName = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_DEPARTURE_TIME),
                description = getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_DEPARTURE_TIME),
                mandatory = true,
                mdocNamespace = BOARDING_PASS_NS,
                icon = Icon.TODAY,
                sampleValue = Clock.System.now().toDataItemDateTimeString()
            )
        }.build()
    }
}
