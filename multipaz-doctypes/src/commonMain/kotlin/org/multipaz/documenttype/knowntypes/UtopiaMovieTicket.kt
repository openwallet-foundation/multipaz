package org.multipaz.documenttype.knowntypes

import kotlinx.datetime.parse
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.documenttype.StringOption
import kotlin.time.Instant

/**
 * Object containing the metadata of the Utopia Movie Ticket Document Type.
 */
object UtopiaMovieTicket {
    const val MOVIE_TICKET_VCT = "https://utopia.example.com/vct/movieticket"

    /**
     * Build the Movie Ticket Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Movie ticket")
            .addJsonDocumentType(type = MOVIE_TICKET_VCT, keyBound = false)
            .addJsonAttribute(
                DocumentAttributeType.String,
                "ticket_id",
                "Ticket number",
                "Ticket identification/reference number issued at the purchase time.",
                Icon.NUMBERS,
                JsonPrimitive(SampleData.TICKET_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "cinema",
                "Cinema theater",
                "Cinema theater name, and/or address/location of the admission.",
                Icon.PLACE,
                JsonPrimitive(SampleData.CINEMA)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "movie",
                "Movie title",
                "Movie name, title, and any other show identification information.",
                Icon.TODAY,
                JsonPrimitive(SampleData.MOVIE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.DateTime,
                identifier = "show_date_time",
                displayName = "Date and time of the show",
                description = "Date and time when the movie starts",
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.MOVIE_DATE_TIME)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption("NR", "NR - Not Rated"),
                        StringOption("G", "G – General Audiences"),
                        StringOption("PG", "PG – Parental Guidance Suggested"),
                        StringOption("PG-13", "PG-13 – Parents Strongly Cautioned"),
                        StringOption("R", "R – Restricted"),
                        StringOption("NC-17", "NC-17 – Adults Only"),
                    )
                ),
                "movie_rating",
                "Age rating code",
                "Movie rating code for age restrictions.",
                Icon.TODAY,
                JsonPrimitive(SampleData.MOVIE_RATING)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "theater_id",
                "Theater",
                "Name or number of the theater in a multi-theater cinema building.",
                Icon.TODAY,
                JsonPrimitive(SampleData.THEATRE_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "seat_id",
                "Seat",
                "Seat number or code (e.g. row/seat).",
                Icon.NUMBERS,
                JsonPrimitive(SampleData.THEATRE_SEAT)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "parking_option",
                "Parking",
                "Flag if car parking is prepaid with the ticket purchase.",
                Icon.DIRECTIONS_CAR,
                JsonPrimitive(SampleData.CINEMA_PARKING)
            )
            .addJsonAttribute(
                DocumentAttributeType.Picture,
                "poster",
                "Movie poster",
                description = "Movie Poster",
                Icon.IMAGE
            )
            .addSampleRequest(
                id = "is_parking_prepaid",
                displayName = "Prepaid parking",
                jsonClaims = listOf("parking_option")
            )
            .addSampleRequest(
                id = "ticket_id",
                displayName = "Ticket number",
                jsonClaims = listOf(
                    "ticket_id",
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = "All data elements",
                jsonClaims = listOf()
            )
            .build()
    }
}