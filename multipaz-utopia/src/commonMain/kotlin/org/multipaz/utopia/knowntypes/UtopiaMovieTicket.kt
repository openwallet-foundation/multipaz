package org.multipaz.utopia.knowntypes

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.documenttype.StringOption
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.SampleData

/**
 * Object containing the metadata of the Utopia Movie Ticket Document Type.
 */
object UtopiaMovieTicket {
    const val MOVIE_TICKET_VCT = "https://utopia.example.com/vct/movieticket"

    /**
     * Build the Movie Ticket Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_MOVIE_TICKET))
            .addJsonDocumentType(type = MOVIE_TICKET_VCT, keyBound = false)
            .addJsonAttribute(
                DocumentAttributeType.String,
                "ticket_id",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_TICKET_NUMBER),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_TICKET_NUMBER),
                Icon.NUMBERS,
                JsonPrimitive(SampleData.TICKET_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "cinema",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_CINEMA_THEATER),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_CINEMA_THEATER),
                Icon.PLACE,
                JsonPrimitive(SampleData.CINEMA)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "movie",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_MOVIE_TITLE),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_MOVIE_TITLE),
                Icon.TODAY,
                JsonPrimitive(SampleData.MOVIE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.DateTime,
                identifier = "show_date_time",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_DATE_AND_TIME_OF_SHOW),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_DATE_AND_TIME_OF_SHOW),
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
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_AGE_RATING_CODE),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_AGE_RATING_CODE),
                Icon.TODAY,
                JsonPrimitive(SampleData.MOVIE_RATING)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "theater_id",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_THEATER),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_THEATER),
                Icon.TODAY,
                JsonPrimitive(SampleData.THEATRE_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "seat_id",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_SEAT),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_SEAT),
                Icon.NUMBERS,
                JsonPrimitive(SampleData.THEATRE_SEAT)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "parking_option",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_PARKING),
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_PARKING),
                Icon.DIRECTIONS_CAR,
                JsonPrimitive(SampleData.CINEMA_PARKING)
            )
            .addJsonAttribute(
                DocumentAttributeType.Picture,
                "poster",
                getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_ATTRIBUTE_MOVIE_POSTER),
                description = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_DESCRIPTION_MOVIE_POSTER),
                Icon.IMAGE
            )
            .addSampleRequest(
                id = "is_parking_prepaid",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_PREPAID_PARKING),
                jsonClaims = listOf("parking_option")
            )
            .addSampleRequest(
                id = "ticket_id",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_TICKET_NUMBER),
                jsonClaims = listOf(
                    "ticket_id",
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.MOVIE_TICKET_REQUEST_ALL_DATA_ELEMENTS),
                jsonClaims = listOf()
            )
            .build()
    }
}
