package org.multipaz.testapp

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.multipaz.util.Logger
import org.multipaz.util.toHex

actual object NfcObserveModeHelper {
    private const val TAG = "NfcObserveModeHelper"
    private const val MAX_RECORDED_FRAMES = 500

    private var enabledInSettingsValue = false
    private var forceEnabledValue = false
    private var explicitlyInhibitedValue = false

    actual var isEnabled: Boolean
        get() = enabledInSettingsValue
        set(value) {
            enabledInSettingsValue = value
            updateState()
        }

    actual var isForceEnabledAndCapturingAllFrames: Boolean
        get() = forceEnabledValue
        set(value) {
            forceEnabledValue = value
            updateState()
        }

    private val _state = MutableStateFlow(NfcObserveModeState())
    actual val state: StateFlow<NfcObserveModeState> = _state.asStateFlow()

    private val _pollingFrames = MutableStateFlow<List<NfcPollingFrame>>(emptyList())
    actual val pollingFrames: StateFlow<List<NfcPollingFrame>> = _pollingFrames.asStateFlow()

    actual fun clearPollingFrames() {
        _pollingFrames.value = emptyList()
    }

    internal fun recordPollingFrames(frames: List<NfcPollingFrame>) {
        for (item in frames) {
            Logger.i(
                TAG,
                "Observed frame #${item.id}: type=${item.type} data=${item.data.toHex()} " +
                    "gain=${item.vendorSpecificGain} ts=${item.timestampMicros}us " +
                    "autoTransact=${item.triggeredAutoTransact}"
            )
        }
        _pollingFrames.update { existing ->
            val combined = existing + frames
            if (combined.size > MAX_RECORDED_FRAMES) {
                combined.takeLast(MAX_RECORDED_FRAMES)
            } else {
                combined
            }
        }
    }

    private fun updateState() {
        _state.update {
            it.copy(
                isEnabledInSettings = enabledInSettingsValue,
                isForceEnabled = forceEnabledValue,
                isExplicitlyInhibited = explicitlyInhibitedValue,
            )
        }
    }

    actual fun inhibitObserveModeForTransaction() {}

    actual fun inhibitObserveMode() {
        explicitlyInhibitedValue = true
        updateState()
    }

    actual fun uninhibitObserveMode() {
        explicitlyInhibitedValue = false
        updateState()
    }
}

@Composable
actual fun rememberInhibitNfcObserveMode() {
}

