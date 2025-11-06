package org.multipaz.nfc

import kotlinx.io.bytestring.ByteString

/**
 * Options used to influence NFC scanning.
 *
 * @property pollingFrameData data to insert into the polling frames emited by the NFC tag reader or `null`.
 */
data class NfcScanOptions(
    val pollingFrameData: ByteString? = null
)