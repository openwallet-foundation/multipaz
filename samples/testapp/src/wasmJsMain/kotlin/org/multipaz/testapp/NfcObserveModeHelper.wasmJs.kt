package org.multipaz.testapp

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object NfcObserveModeHelper {

    actual var isEnabled: Boolean
        get() = false
        set(value) {}

    actual var isForceEnabledAndCapturingAllFrames: Boolean
        get() = false
        set(value) {}

    private val _state = MutableStateFlow(NfcObserveModeState())
    actual val state: StateFlow<NfcObserveModeState> = _state.asStateFlow()

    private val _pollingFrames = MutableStateFlow<List<NfcPollingFrame>>(emptyList())
    actual val pollingFrames: StateFlow<List<NfcPollingFrame>> = _pollingFrames.asStateFlow()

    actual fun clearPollingFrames() {}

    actual fun inhibitObserveModeForTransaction() {}

    actual fun inhibitObserveMode() {}

    actual fun uninhibitObserveMode() {}
}

@Composable
actual fun rememberInhibitNfcObserveMode() {
}

