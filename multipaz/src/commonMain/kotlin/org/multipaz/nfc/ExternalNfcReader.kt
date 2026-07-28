package org.multipaz.nfc

import kotlinx.coroutines.flow.Flow
import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

/**
 * Base class for external NFC readers.
 *
 * @property id an identifier for the reader.
 * @property addedAt the point in time the reader was added
 * @property displayName a human-readable name for the reader.
 * @property userDisplayName a user-assigned custom display name for the reader, or null if not set.
 */
@CborSerializable
sealed class ExternalNfcReader(
    open val id: String,
    open val addedAt: Instant,
    open val displayName: String,
    open val userDisplayName: String? = null,
) {
    internal var store: ExternalNfcReaderStore?
        get() = storeMap[id]
        set(value) {
            if (value != null) {
                storeMap[id] = value
            } else {
                storeMap.remove(id)
            }
        }

    /**
     * Starts observing the state of a reader.
     *
     * @return a [Flow] with state of the reader.
     */
    abstract fun observeState(): Flow<ExternalNfcReaderState>

    /**
     * Requests the user for permission to use the device.
     *
     * This blocks until the user grants or denies the permission.
     *
     * @return whether permission was granted.
     */
    abstract suspend fun requestPermission(): Boolean

    /**
     * Gets a [NfcTagReader] to perform NFC reading operations.
     *
     * @return a [NfcTagReader].
     */
    abstract suspend fun getNfcTagReader(): NfcTagReader

    /**
     * Sets a user-customized display name for the reader.
     *
     * @param userDisplayName the new user display name, or null to clear it.
     */
    abstract suspend fun setUserDisplayName(userDisplayName: String?)

    companion object {
        private val storeMap = mutableMapOf<String, ExternalNfcReaderStore>()
    }
}
