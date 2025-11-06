package org.multipaz.testapp

actual object NfcObserveModeHelper {

    actual var isEnabled: Boolean
        get() = false
        set(value) {}

    actual fun inhibitObserveModeForTransaction() {}

    actual fun inhibitObserveMode() {}

    actual fun uninhibitObserveMode() {}
}
