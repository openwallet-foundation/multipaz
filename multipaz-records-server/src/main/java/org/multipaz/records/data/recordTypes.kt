package org.multipaz.records.data

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.StringOption
import org.multipaz.documenttype.knowntypes.Options

/**
 * Describes schema of the data stored in [Identity]. Record type with the identifier "core"
 * describes core data, other record types describe possible records.
 */
val recordTypes = RecordType.buildMap {
    addComplex("core") {
        displayName = "Core personal information"
        addString(
            identifier = "family_name",
            displayName = "Family Name",
            description = "Last name, surname, or primary identifier, of the person.",
            icon = Icon.PERSON,
        )
        addString(
            identifier = "given_name",
            displayName = "Given Names",
            description = "First name(s), other name(s), or secondary identifier, of the person",
            icon = Icon.PERSON,
        )
        addDate(
            identifier = "birth_date",
            displayName = "Date of Birth",
            description = "Day, month and year on which the person was born. If unknown, approximate date of birth",
            icon = Icon.TODAY,
        )
        addPicture(
            identifier = "portrait",
            displayName = "Photo of the person",
            description = "A reproduction of the person’s portrait.",
            icon = Icon.FACE,
        )
        addDate(
            identifier = "portrait_capture_date",
            displayName = "Portrait Image Timestamp",
            description = "Date when portrait was taken",
            icon = Icon.TODAY,
        )
        addPrimitive(
            type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
            identifier = "sex",
            displayName ="Sex",
            description = "Person’s sex",
            icon = Icon.EMERGENCY,
        )
        addString(
            identifier = "birth_place",
            displayName = "Place of Birth",
            description = "Country and municipality or state/province where the person was born",
            icon = Icon.PLACE,
        )
        addComplex("address") {
            displayName = "Address"
            description = "Address"
            icon = Icon.PLACE
            addString(
                identifier = "formatted",
                displayName = "Fully formatted",
                description = "Full address as text",
                icon = Icon.PLACE,
            )
            addPrimitive(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "country",
                displayName = "Country",
                description = "Country",
                icon = Icon.FLAG,
            )
            addString(
                identifier = "region",
                displayName = "State/Province/Region",
                description = "Administrative unit in the country",
                icon = Icon.PLACE,
            )
            addString(
                identifier = "locality",
                displayName = "City",
                description = "City/Town/Village",
                icon = Icon.LOCATION_CITY,
            )
            addString(
                identifier = "postal_code",
                displayName = "Postal Code",
                description = "National postal code",
                icon = Icon.NUMBERS,
            )
            addString(
                identifier = "street",
                displayName = "Street",
                description = "Name of the street",
                icon = Icon.DIRECTIONS,
            )
            addString(
                identifier = "house_number",
                displayName = "House Number",
                description = "House Number",
                icon = Icon.HOUSE,
            )
            addString(
                identifier = "unit",
                displayName = "Apartment Number",
                description = "Apartment/Unit Number",
                icon = Icon.APARTMENT,
            )
            addString(
                identifier = "us_county_code",
                displayName = "If in US, County Code",
                description = "Three-digit code",
                icon = Icon.NUMBERS,
            )
        }
        addString(
            identifier = "family_name_national_character",
            displayName = "Family Name (Local)",
            description = "The family name spelled in national alphabet",
            icon = Icon.LANGUAGE_JAPANESE_KANA,
        )
        addString(
            identifier = "given_name_national_character",
            displayName = "Given Name (Local)",
            description = "The given name spelled in national alphabet",
            icon = Icon.LANGUAGE_JAPANESE_KANA,
        )
        addPrimitiveList(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "nationality",
            displayName = "Nationalities",
            description = "List of nationalities",
            icon = Icon.FLAG,
        )
        addPicture(
            identifier = "signature_usual_mark",
            displayName = "Signature",
            description = "Image of the signature or usual mark of the mDL holder,",
            icon = Icon.SIGNATURE,
        )
        addString(
            identifier = "utopia_id_number",
            displayName = "Utopia id number",
            description = "Unique and immutable number assigned to everyone by Utopia Registry",
            icon = Icon.ACCOUNT_BOX,
        )
    }
    addComplex("mDL") {
        displayName = "Driver's License"
        addDate(
            identifier = "issue_date",
            displayName = "Date of Issue",
            description = "Date when mDL was issued",
            icon = Icon.TODAY,
        )
        addDate(
            identifier = "expiry_date",
            displayName = "Date of Expiry",
            description = "Date when mDL expires",
            icon = Icon.TODAY,
        )
        addPrimitive(
            type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
            identifier = "issuing_country",
            displayName = "Issuing Country",
            description = "Alpha-2 country code, as defined in ISO 3166-1, of the issuing authority’s country or territory",
            icon = Icon.ACCOUNT_BALANCE,
        )
        addString(
            identifier = "issuing_authority",
            displayName = "Issuing Authority",
            description = "Issuing authority name.",
            icon = Icon.ACCOUNT_BALANCE,
        )
        addString(
            identifier = "document_number",
            displayName = "License Number",
            description = "The number assigned or calculated by the issuing authority.",
            icon = Icon.NUMBERS,
        )
        addComplexList(
            identifier ="driving_privileges",
            displayName = "Driving Privileges",
            description = "Driving privileges of the mDL holder",
            icon = Icon.DIRECTIONS_CAR
        ) {
            addPrimitive(
                type = DocumentAttributeType.StringOptions(Options.VEHICLE_CATEGORY_CODE_ISO_IEC_18013_1_ANNEX_B),
                "vehicle_category_code",
                displayName = "Vehicle Category",
                description = "Vehicle type that mDL holder is licensed to drive"
            )
            addDate(
                identifier = "issue_date",
                displayName = "Date of Issue",
                description = "Date when mDL holder was licensed for this type of vehicle",
            )
            addDate(
                identifier = "expiry_date",
                displayName = "Date of Expiry",
                description = "Date until mDL holder is licensed for this type of vehicle",
            )
        }
        addPrimitive(
            type = DocumentAttributeType.StringOptions(Options.DISTINGUISHING_SIGN_ISO_IEC_18013_1_ANNEX_F),
            identifier = "un_distinguishing_sign",
            displayName ="UN Distinguishing Sign",
            description = "Distinguishing sign of the issuing country",
            icon = Icon.LANGUAGE,
        )
        addString(
            identifier = "administrative_number",
            displayName = "Administrative Number",
            description = "An audit control number assigned by the issuing authority",
            icon = Icon.NUMBERS
        )
        addNumber(
            identifier = "height",
            displayName = "Height",
            description = "mDL holder’s height in centimetres",
            icon = Icon.EMERGENCY,
        )
        addNumber(
            identifier = "weight",
            displayName ="Weight",
            description = "mDL holder’s weight in kilograms",
            icon = Icon.EMERGENCY
        )
        addPrimitive(
            type = DocumentAttributeType.StringOptions(
        listOf(
                    StringOption(null, "(not set)"),
                    StringOption("black", "Black"),
                    StringOption("blue", "Blue"),
                    StringOption("brown", "Brown"),
                    StringOption("dichromatic", "Dichromatic"),
                    StringOption("grey", "Grey"),
                    StringOption("green", "Green"),
                    StringOption("hazel", "Hazel"),
                    StringOption("maroon", "Maroon"),
                    StringOption("pink", "Pink"),
                    StringOption("unknown", "Unknown")
                )
            ),
            identifier = "eye_colour",
            displayName = "Eye Color",
            description = "mDL holder’s eye color",
            icon = Icon.PERSON,
        )
        addPrimitive(
            type = DocumentAttributeType.StringOptions(
                listOf(
                    StringOption(null, "(not set)"),
                    StringOption("bald", "Bald"),
                    StringOption("black", "Black"),
                    StringOption("blond", "Blond"),
                    StringOption("brown", "Brown"),
                    StringOption("grey", "Grey"),
                    StringOption("red", "Red"),
                    StringOption("auburn", "Auburn"),
                    StringOption("sandy", "Sandy"),
                    StringOption("white", "White"),
                    StringOption("unknown", "Unknown"),
                )
            ),
            identifier = "hair_colour",
            displayName = "Hair Color",
            description = "mDL holder’s hair color",
            icon = Icon.PERSON,
        )
    }
    addComplex("naturalization") {
        displayName = "Utopia Naturalization"
        addDate(
            identifier = "naturalization_date",
            displayName = "Date of Naturalization",
            description = "Date when the person was granted Utopia citizenship",
            icon = Icon.TODAY,
        )
    }
    addComplex("movie") {
        displayName = "Movie Ticket"
        addString(
            identifier = "movie_title",
            displayName = "Movie Title",
            description = "Title of the movie for which the ticket is valid",
            icon = Icon.PANORAMA_WIDE_ANGLE,
        )
        addDateTime(
            identifier = "show_date_time",
            displayName = "Date and time of the show",
            description = "Date and time when the movie starts",
            icon = Icon.TODAY,
        )
        addString(
            identifier = "ticket_id",
            displayName = "Ticket Number",
            description = "Ticket identification/reference number issued at the purchase time.",
            icon = Icon.NUMBERS,
        )
        addString(
            identifier = "cinema",
            displayName = "Cinema Theater",
            description = "Cinema theater name, and/or address/location of the admission.",
            icon = Icon.PLACE,
        )
        addPrimitive(
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
            displayName = "Age Rating Code",
            description = "Movie rating code for age restrictions.",
            icon = Icon.TODAY,
        )
        addString(
            identifier = "theater_id",
            displayName = "Theater",
            description = "Name or number of the theater in a multi-theater cinema building.",
            Icon.TODAY,
        )
        addString(
            identifier = "seat_id",
            displayName = "Seat",
            description = "Seat number or code (e.g. row/seat).",
            Icon.NUMBERS,
        )
        addPrimitive(
            type = DocumentAttributeType.Boolean,
            identifier = "parking_option",
            displayName = "Parking",
            description = "Flag if car parking is prepaid with the ticket purchase.",
            Icon.DIRECTIONS_CAR,
        )
        addPicture(
            identifier = "poster",
            displayName = "Movie Poster",
            description = "Poster for the movie",
            icon = Icon.IMAGE,
        )
    }
    addComplex("wholesale") {
        displayName = "Utopia Wholesale Loyalty ID"
        addString(
            identifier = "membership_number",
            displayName = "Membership ID",
            description = "Person identifier of the Loyalty ID holder",
            icon = Icon.NUMBERS,
        )
        addString(
            identifier = "tier",
            displayName = "Tier",
            description = "Membership tier (basic, silver, gold, platinum, elite)",
            icon = Icon.STARS,
        )
        addDate(
            identifier = "issue_date",
            displayName = "Date of Issue",
            description = "Date when the Loyalty ID was issued",
            icon = Icon.TODAY,
        )
        addDate(
            identifier = "expiry_date",
            displayName = "Date of Expiry",
            description = "Date when the Loyalty ID expires",
            icon = Icon.TODAY,
        )

    }
}