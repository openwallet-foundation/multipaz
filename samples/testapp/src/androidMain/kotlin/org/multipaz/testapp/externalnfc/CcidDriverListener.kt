package org.multipaz.testapp.externalnfc

/**
 * Listener interface for receiving notifications about smart card events.
 * Implement this interface to receive callbacks when a card is inserted or removed
 * from the CCID reader.
 */
interface CcidDriverListener {
    /**
     * Called when a smart card is inserted into the reader.
     */
    fun onCardInserted()
    /**
     * Called when a smart card is removed from the reader.
     */
    fun onCardRemoved()
}
