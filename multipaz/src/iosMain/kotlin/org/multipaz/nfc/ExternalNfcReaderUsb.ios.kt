package org.multipaz.nfc

import kotlinx.coroutines.flow.Flow

internal actual fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState> {
    throw NotImplementedError("Not implemented on this platform")
}

internal actual suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean {
    throw NotImplementedError("Not implemented on this platform")
}

internal actual suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader {
    throw NotImplementedError("Not implemented on this platform")
}
