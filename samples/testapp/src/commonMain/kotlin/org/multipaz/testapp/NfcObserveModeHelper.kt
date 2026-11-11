package org.multipaz.testapp

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.bytestring.ByteString
import kotlin.time.Instant

/**
 * Type of NFC polling frame observed by the NFC controller in Observe Mode.
 */
enum class NfcPollingFrameType {
    /** NFC-A (ISO/IEC 14443-3A) polling frame. */
    A,
    /** NFC-B (ISO/IEC 14443-3B) polling frame. */
    B,
    /** NFC-F (FeliCa / JIS X 6319-4) polling frame. */
    F,
    /** NFC-V (ISO/IEC 15693) polling frame. */
    V,
    /** Remote RF field turned ON. */
    REMOTE_FIELD_ON,
    /** Remote RF field turned OFF. */
    REMOTE_FIELD_OFF,
    /** Unknown or vendor-specific polling frame. */
    UNKNOWN,
}

/**
 * Parsed Enhanced Contactless Polling (ECP) details for an [NfcPollingFrame].
 *
 * @property version ECP format version (1 or 2).
 * @property terminalDescription human-readable description of the terminal/reader type.
 * @property tciHex hex representation of the 3-byte Terminal Capability Information (TCI).
 * @property isIdentityReader whether this frame identifies an ISO/IEC 18013-5 Identity Reader (`6a028103...` / `6a020103...`).
 */
data class ParsedEcpPollingFrame(
    val version: Int,
    val terminalDescription: String,
    val tciHex: String,
    val isIdentityReader: Boolean,
)

/**
 * A single NFC polling frame observed while in Observe Mode.
 *
 * @property id monotonically increasing sequence number of the observed frame.
 * @property type the [NfcPollingFrameType] of this frame.
 * @property data raw polling frame payload bytes.
 * @property vendorSpecificGain vendor-specific RF gain / signal strength indicator.
 * @property timestampMicros hardware/controller timestamp in microseconds.
 * @property triggeredAutoTransact whether the NFC controller automatically exited Observe Mode on this frame.
 * @property observedAt wall-clock [Instant] when the frame was delivered to the host.
 */
data class NfcPollingFrame(
    val id: Long,
    val type: NfcPollingFrameType,
    val data: ByteString,
    val vendorSpecificGain: Int,
    val timestampMicros: Long,
    val triggeredAutoTransact: Boolean,
    val observedAt: Instant,
) {
    /**
     * Attempts to parse [data] as an Apple Enhanced Contactless Polling (ECP) frame (`0x6A ...`).
     */
    fun parseEcp(): ParsedEcpPollingFrame? {
        val bytes = data.toByteArray()
        if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != 0x6A) {
            return null
        }
        if (bytes.size >= 5 && (bytes[1].toInt() and 0xFF) == 0x01) {
            val tci0 = bytes[2].toInt() and 0xFF
            val tci1 = bytes[3].toInt() and 0xFF
            val tci2 = bytes[4].toInt() and 0xFF
            val tciHex = byteArrayOf(tci0.toByte(), tci1.toByte(), tci2.toByte()).toHexString()
            val desc = when {
                tci0 == 0x00 && tci1 == 0x00 && tci2 == 0x00 -> "ECP v1: Transit (generic)"
                tci0 == 0x03 -> "ECP v1: Transit (TCI $tciHex)"
                else -> "ECP v1 (TCI $tciHex)"
            }
            return ParsedEcpPollingFrame(
                version = 1,
                terminalDescription = desc,
                tciHex = tciHex,
                isIdentityReader = false
            )
        }
        if (bytes.size >= 5 && (bytes[1].toInt() and 0xFF) == 0x02) {
            val rawType = bytes[2].toInt() and 0xFF
            val openLoop = (rawType and 0x80) != 0
            val terminalType = rawType and 0x7F
            val subType = bytes[3].toInt() and 0xFF
            val tci2 = bytes[4].toInt() and 0xFF
            val tciHex = byteArrayOf(rawType.toByte(), subType.toByte(), tci2.toByte()).toHexString()
            val subTypeHex = "0x" + subType.toByte().toHexString()
            val terminalTypeHex = "0x" + terminalType.toByte().toHexString()
            val isIdentityReader = (terminalType == 0x01 && subType == 0x03)
            val typeDesc = when (terminalType) {
                0x01 -> when (subType) {
                    0x01 -> "Transit"
                    0x02 -> "Access"
                    0x03 -> "Identity Reader (ISO 18013-5 / mdoc)"
                    0x05 -> "Automaker / Car Key"
                    else -> "VAS / Type 1 (sub-type $subTypeHex)"
                }
                0x02 -> when (subType) {
                    0x01 -> "Access (HomeKit / Gym)"
                    else -> "Access / Type 2 (sub-type $subTypeHex)"
                }
                else -> "Unknown Terminal Type $terminalTypeHex (sub-type $subTypeHex)"
            }
            val loopDesc = if (openLoop) "Open-Loop (EMV + VAS)" else "Closed-Loop (VAS only)"
            return ParsedEcpPollingFrame(
                version = 2,
                terminalDescription = "ECP v2: $typeDesc [$loopDesc]",
                tciHex = tciHex,
                isIdentityReader = isIdentityReader
            )
        }
        return null
    }
}

/**
 * Snapshot of the current Observe Mode state.
 *
 * @property isSupported whether the platform and NFC controller support Observe Mode.
 * @property isEnabledInSettings whether Observe Mode is enabled in app settings.
 * @property isForceEnabled whether Observe Mode is currently forced on (e.g. by the Observe Mode test screen).
 * @property isEnabledOnAdapter whether Observe Mode is currently active on the underlying NFC controller.
 * @property isExplicitlyInhibited whether Observe Mode is explicitly inhibited (e.g. on DocumentViewerScreen).
 * @property isInhibitedForTransaction whether Observe Mode is temporarily inhibited for an ongoing transaction.
 */
data class NfcObserveModeState(
    val isSupported: Boolean = false,
    val isEnabledInSettings: Boolean = false,
    val isForceEnabled: Boolean = false,
    val isEnabledOnAdapter: Boolean = false,
    val isExplicitlyInhibited: Boolean = false,
    val isInhibitedForTransaction: Boolean = false,
)

/**
 * Simple abstraction for managing Observe Mode for Test App.
 *
 * At some point this could be cleaned up and moved to the Multipaz library.
 */
expect object NfcObserveModeHelper {

    /**
     * Whether observe mode is enabled in app settings.
     */
    var isEnabled: Boolean

    /**
     * Whether observe mode is forced on and capturing all polling frames (used by the Observe Mode screen).
     */
    var isForceEnabledAndCapturingAllFrames: Boolean

    /**
     * Reactive state of Observe Mode.
     */
    val state: StateFlow<NfcObserveModeState>

    /**
     * Reactive list of observed [NfcPollingFrame]s (oldest to newest).
     */
    val pollingFrames: StateFlow<List<NfcPollingFrame>>

    /**
     * Clears all recorded [pollingFrames].
     */
    fun clearPollingFrames()

    /**
     * When this is called observe mode is disabled for 5 seconds to allow a transaction to go through.
     */
    fun inhibitObserveModeForTransaction()

    /**
     * Inhibits observe mode.
     *
     * This inhibits observe mode until [uninhibitObserveMode] is called.
     *
     * Generally this should be called when on the screen where the user has selected a document. The
     * [rememberInhibitNfcObserveMode] composable can be used to do this automatically.
     */
    fun inhibitObserveMode()

    /**
     * Uninhibits observe mode.
     *
     * See [inhibitObserveMode] for how to use this.
     */
    fun uninhibitObserveMode()
}

/**
 * Composable for calling [NfcObserveModeHelper.inhibitObserveMode] and
 * [NfcObserveModeHelper.uninhibitObserveMode] when entering respectively
 * leaving a composable.
 */
@Composable
expect fun rememberInhibitNfcObserveMode()

