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
                DocumentAttributeType.String,
                "passenger_name",
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_PASSENGER_NAME),
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_PASSENGER_NAME),
                true,
                BOARDING_PASS_NS,
                Icon.PERSON,
                "Erika Mustermann".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.String,
                "flight_number",
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_FLIGHT_NUMBER),
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_FLIGHT_NUMBER),
                true,
                BOARDING_PASS_NS,
                Icon.AIRPORT_SHUTTLE,
                "United 815".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.String,
                "seat_number",
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_SEAT_NUMBER),
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_SEAT_NUMBER),
                true,
                BOARDING_PASS_NS,
                Icon.DIRECTIONS,
                "12A".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.DateTime,
                "departure_time",
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_ATTRIBUTE_DEPARTURE_TIME),
                getLocalizedString(GeneratedStringKeys.BOARDING_PASS_DESCRIPTION_DEPARTURE_TIME),
                true,
                BOARDING_PASS_NS,
                Icon.TODAY,
                Clock.System.now().toDataItemDateTimeString()
            )
        }.build()
    }
}
