package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.StringOption
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.utopia.localization.GeneratedStringKeys
import org.multipaz.utopia.localization.LocalizedStrings

/**
 * Object containing the metadata of the Utopia Movie Ticket Document Type.
 */
object UtopiaMovieTicket {
    const val MOVIE_TICKET_DOCTYPE = "org.multipaz.movieticket.1"
    const val MOVIE_TICKET_NAMESPACE = "org.multipaz.movieticket.1"
    const val MOVIE_TICKET_VCT = "https://utopia.example.com/vct/movieticket"

    /**
     * Build the Movie Ticket Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_MOVIE_TICKET))
            .addMdocDocumentType(mdocDocType = MOVIE_TICKET_DOCTYPE, keyBound = false)
            .addJsonDocumentType(type = MOVIE_TICKET_VCT, keyBound = false)
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "ticket_id",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_TICKET_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_TICKET_NUMBER),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.TICKET_NUMBER.toString().toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.TICKET_NUMBER)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "cinema",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_CINEMA_THEATER),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_CINEMA_THEATER),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.PLACE,
                sampleValueMdoc = SampleData.CINEMA.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.CINEMA)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "movie",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_MOVIE_TITLE),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_MOVIE_TITLE),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.MOVIE.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.MOVIE)
            )
            .addAttribute(
                type = DocumentAttributeType.DateTime,
                identifier = "show_date_time",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_DATE_AND_TIME_OF_SHOW),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_DATE_AND_TIME_OF_SHOW),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.MOVIE_DATE_TIME.toDataItemDateTimeString(),
                sampleValueJson = JsonPrimitive(SampleData.MOVIE_DATE_TIME)
            )
            .addAttribute(
                type = DocumentAttributeType.StringOptions(
                    listOf(
                        StringOption("NR", "NR - Not Rated"),
                        StringOption("G", "G – General Audiences"),
                        StringOption("PG", "PG – Parental Guidance Suggested"),
                        StringOption("PG-13", "PG-13 – Parents Strongly Cautioned"),
                        StringOption("R", "R – Restricted"),
                        StringOption("NC-17", "NC-17 – Adults Only"),
                    )
                ),
                identifier = "movie_rating",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_AGE_RATING_CODE),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_AGE_RATING_CODE),
                mandatory = false,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.MOVIE_RATING.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.MOVIE_RATING)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "theater_id",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_THEATER),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_THEATER),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.TODAY,
                sampleValueMdoc = SampleData.THEATRE_NAME.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.THEATRE_NAME)
            )
            .addAttribute(
                type = DocumentAttributeType.String,
                identifier = "seat_id",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_SEAT),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_SEAT),
                mandatory = true,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValueMdoc = SampleData.THEATRE_SEAT.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.THEATRE_SEAT)
            )
            .addAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "parking_option",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_PARKING),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_PARKING),
                mandatory = false,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.DIRECTIONS_CAR,
                sampleValueMdoc = SampleData.CINEMA_PARKING.toDataItem(),
                sampleValueJson = JsonPrimitive(SampleData.CINEMA_PARKING)
            )
            .addAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "poster",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_MOVIE_POSTER),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_MOVIE_POSTER),
                mandatory = false,
                mdocNamespace = MOVIE_TICKET_NAMESPACE,
                icon = Icon.IMAGE
            )
            .addSampleRequest(
                id = "is_parking_prepaid",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_PREPAID_PARKING),
                mdocDataElements = mapOf(
                    MOVIE_TICKET_NAMESPACE to mapOf("parking_option" to false)
                ),
                jsonClaims = listOf("parking_option")
            )
            .addSampleRequest(
                id = "ticket_id",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_TICKET_NUMBER),
                mdocDataElements = mapOf(
                    MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)
                ),
                jsonClaims = listOf(
                    "ticket_id",
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    MOVIE_TICKET_NAMESPACE to mapOf()
                ),
                jsonClaims = listOf()
            )
            .build()
    }
}
