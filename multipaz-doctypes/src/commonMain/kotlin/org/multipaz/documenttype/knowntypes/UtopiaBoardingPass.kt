package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlin.time.Clock

/**
 * An example of what a boarding pass doctype could look like.
 *
 */
object UtopiaBoardingPass {
    const val BOARDING_PASS_DOCTYPE = "org.multipaz.example.boarding-pass.1"
    const val BOARDING_PASS_NS = "org.multipaz.example.boarding-pass.1"

    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Boarding Pass").apply {
            addMdocDocumentType(BOARDING_PASS_DOCTYPE)
            addMdocAttribute(
                DocumentAttributeType.String,
                "passenger_name",
                "Passenger name",
                "Last name, surname, or primary identifier, of the mDL holder.",
                true,
                BOARDING_PASS_NS,
                Icon.PERSON,
                "Erika Mustermann".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.String,
                "flight_number",
                "Flight number",
                "The flight number",
                true,
                BOARDING_PASS_NS,
                Icon.AIRPORT_SHUTTLE,
                "United 815".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.String,
                "seat_number",
                "Seat number",
                "The seat number",
                true,
                BOARDING_PASS_NS,
                Icon.DIRECTIONS,
                "12A".toDataItem()
            )
            addMdocAttribute(
                DocumentAttributeType.DateTime,
                "departure_time",
                "Departure time",
                "The date of time of departure",
                true,
                BOARDING_PASS_NS,
                Icon.TODAY,
                Clock.System.now().toDataItemDateTimeString()
            )
        }.build()
    }
}
