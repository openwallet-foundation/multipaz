package org.multipaz.testapp

import androidx.compose.runtime.Composable

actual object NfcObserveModeHelper {

    actual var isEnabled: Boolean
        get() = false
        set(value) {}

    actual fun inhibitObserveModeForTransaction() {}

    actual fun inhibitObserveMode() {}

    actual fun uninhibitObserveMode() {}
}

@Composable
actual fun rememberInhibitNfcObserveMode() {
}
