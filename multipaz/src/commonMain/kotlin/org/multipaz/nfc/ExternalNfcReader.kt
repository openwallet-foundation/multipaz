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
 */
@CborSerializable
sealed class ExternalNfcReader(
    open val id: String,
    open val addedAt: Instant,
    open val displayName: String,
) {
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

    companion object {
    }
}
