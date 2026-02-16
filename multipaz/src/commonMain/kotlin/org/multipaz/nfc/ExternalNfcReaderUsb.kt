package org.multipaz.nfc

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * An external NFC reader connected via USB.
 *
 * @property vendorId the USB vendor ID for the reader.
 * @property productId the USB product ID for the reader.
 */
data class ExternalNfcReaderUsb(
    override val id: String,
    override val addedAt: Instant,
    override val displayName: String,
    val vendorId: Int,
    val productId: Int
): ExternalNfcReader(id, addedAt, displayName) {

    override fun observeState(): Flow<ExternalNfcReaderState> = observeUsbState()

    override suspend fun requestPermission(): Boolean = requestUsbPermission()

    override suspend fun getNfcTagReader(): NfcTagReader = getUsbNfcTagReader()
}

internal expect fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState>

internal expect suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean

internal expect suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader
