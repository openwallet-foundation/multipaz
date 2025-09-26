package org.multipaz.testapp.externalnfc

/**
 * Represents the status of the smart card in the reader.
 */
enum class CardStatus {
    /**
     * Card is present and powered on.
     */
    PRESENT_ACTIVE,

    /**
     * Card is present, but not powered on.
     */
    PRESENT_INACTIVE,

    /**
     * No card is present in the reader.
     */
    ABSENT,

    /**
     * The card status is unknown.
     */
    UNKNOWN
}
