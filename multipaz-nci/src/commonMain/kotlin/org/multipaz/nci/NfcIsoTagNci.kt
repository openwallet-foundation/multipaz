package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.ResponseApdu
import kotlin.concurrent.Volatile

/**
 * [NfcIsoTag] implementation backed by an [NciEngine] over NCI Static RF Connection `0x00`
 * with `RF_INTERFACE_ISO_DEP`.
 *
 * @property activatedEvent details of the `RF_INTF_ACTIVATED_NTF` event that activated this tag.
 */
class NfcIsoTagNci internal constructor(
    private val engine: NciEngine,
    val activatedEvent: NciRfActivatedEvent,
    private val updateMessageCallback: (String) -> Unit,
    private val onCloseCallback: suspend () -> Unit,
) : NfcIsoTag() {

    /**
     * Whether [close] has been called on this tag.
     */
    @Volatile
    var isClosed: Boolean = false
        private set

    // NCI RF_INTERFACE_ISO_DEP handles ISO 14443-4 I-block chaining in controller firmware
    // while NciEngine segments/reassembles large APDUs across NCI Data packets using the PBF bit.
    override val maxTransceiveLength: Int
        get() = 0xFFFF

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        if (isClosed) {
            throw NfcTagLostException("Tag is already closed")
        }
        val encodedCommand = command.encode()
        val encodedResponse = try {
            engine.transceiveApdu(encodedCommand)
        } catch (e: NfcTagLostException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NfcTagLostException("NCI APDU transceive failed", e)
        }
        return try {
            ResponseApdu.decode(encodedResponse)
        } catch (e: Exception) {
            throw NfcTagLostException("Invalid ResponseApdu from tag", e)
        }
    }

    override suspend fun close() {
        if (!isClosed) {
            isClosed = true
            onCloseCallback()
        }
    }

    override suspend fun updateDialogMessage(message: String) {
        updateMessageCallback(message)
    }
}
