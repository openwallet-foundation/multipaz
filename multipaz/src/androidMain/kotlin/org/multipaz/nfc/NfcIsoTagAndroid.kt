package org.multipaz.nfc

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class NfcIsoTagAndroid(
    private val tag: IsoDep,
    private val context: CoroutineContext,
    private val updateMessage: (message: String) -> Unit
): NfcIsoTag() {
    companion object {
        private const val TAG = "NfcIsoTagAndroid"
    }

    override val maxTransceiveLength: Int
        get() = tag.maxTransceiveLength

    override suspend fun close() {
        // This is a no-op on Android
    }

    override suspend fun updateDialogMessage(message: String) {
        updateMessage(message)
    }

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        val encodedCommand = command.encode()
        check(encodedCommand.size <= maxTransceiveLength) {
            "APDU is ${encodedCommand.size} bytes which exceeds maxTransceiveLength of $maxTransceiveLength bytes"
        }
        // Because transceive() blocks the calling thread, we want to ensure this runs in the
        // context such as Dispatchers.IO where it's allowed to do so.
        //
        val responseApduData = withContext(context) {
            try {
                tag.transceive(encodedCommand)
            } catch (e: TagLostException) {
                throw NfcTagLostException("Tag was lost", e)
            }
        }
        return ResponseApdu.decode(responseApduData)
    }
}
