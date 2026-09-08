package org.multipaz.nfc

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * An external NFC reader connected via USB.
 *
 * @property vendorId the USB vendor ID for the reader.
 * @property productId the USB product ID for the reader.
 * @property interfaceIndex the USB interface index for the CCID interface on the reader.
 */
data class ExternalNfcReaderUsb(
    override val id: String,
    override val addedAt: Instant,
    override val displayName: String,
    override val userDisplayName: String? = null,
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int
): ExternalNfcReader(id, addedAt, displayName, userDisplayName) {

    override fun observeState(): Flow<ExternalNfcReaderState> = observeUsbState()

    override suspend fun requestPermission(): Boolean = requestUsbPermission()

    override suspend fun getNfcTagReader(): NfcTagReader = getUsbNfcTagReader()

    override suspend fun setUserDisplayName(userDisplayName: String?) {
        store?.setUserDisplayName(this, userDisplayName)
    }
}

internal expect fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState>

internal expect suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean

internal expect suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader
