package org.multipaz.nfc

import android.nfc.tech.IsoDep
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

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
        val t0 = Clock.System.now()
        Logger.i(TAG, "Sending APDU of ${encodedCommand.size} bytes")
        val responseApduData = withContext(context) {
            try {
                tag.transceive(encodedCommand)
            } catch (e: IOException) {
                // In Android's BasicTagTechnology.transceive() / TransceiveResult.getResponseOrThrow(),
                // the possible IOException cases are:
                // - TagLostException (when mResult == RESULT_TAGLOST)
                // - IOException("Transceive length exceeds supported maximum") (when mResult ==
                //   RESULT_EXCEEDED_LENGTH, which we already rule out above via maxTransceiveLength)
                // - IOException("Transceive failed") (when mResult == RESULT_FAILURE, which the Android
                //   NFC stack frequently reports instead of RESULT_TAGLOST when a tag or HCE device
                //   leaves the RF field while an APDU is in flight, e.g., while showing an AID
                //   disambiguation dialog)
                // - IOException("transceive failed") or IOException("NFC service died")
                // Therefore, any IOException thrown here is effectively a lost tag connection.
                throw NfcTagLostException("Tag was lost", e)
            }
        }
        val duration = Clock.System.now() - t0
        Logger.i(TAG, "Sent APDU of ${encodedCommand.size} and received APDU of ${responseApduData.size} bytes " +
        "in ${duration.inWholeMilliseconds} msec")
        return ResponseApdu.decode(responseApduData)
    }
}
