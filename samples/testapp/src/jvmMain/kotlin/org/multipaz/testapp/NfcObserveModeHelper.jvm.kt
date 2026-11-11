package org.multipaz.testapp

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

actual object NfcObserveModeHelper {
    private val lock = Any()
    private var enabledInSettingsValue = false
    private var forceEnabledValue = false
    private var explicitlyInhibitedValue = false

    actual var isEnabled: Boolean
        get() = synchronized(lock) { enabledInSettingsValue }
        set(value) {
            synchronized(lock) {
                enabledInSettingsValue = value
                updateStateLocked()
            }
        }

    actual var isForceEnabledAndCapturingAllFrames: Boolean
        get() = synchronized(lock) { forceEnabledValue }
        set(value) {
            synchronized(lock) {
                forceEnabledValue = value
                updateStateLocked()
            }
        }

    private val _state = MutableStateFlow(NfcObserveModeState())
    actual val state: StateFlow<NfcObserveModeState> = _state.asStateFlow()

    private val _pollingFrames = MutableStateFlow<List<NfcPollingFrame>>(emptyList())
    actual val pollingFrames: StateFlow<List<NfcPollingFrame>> = _pollingFrames.asStateFlow()

    actual fun clearPollingFrames() {
        _pollingFrames.value = emptyList()
    }

    private fun updateStateLocked() {
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
        synchronized(lock) {
            explicitlyInhibitedValue = true
            updateStateLocked()
        }
    }

    actual fun uninhibitObserveMode() {
        synchronized(lock) {
            explicitlyInhibitedValue = false
            updateStateLocked()
        }
    }
}

@Composable
actual fun rememberInhibitNfcObserveMode() {
}

