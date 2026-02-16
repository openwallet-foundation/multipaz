package org.multipaz.nfc

/**
 * Represents the status of the smart card in the reader.
 */
internal enum class CardStatus {
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
