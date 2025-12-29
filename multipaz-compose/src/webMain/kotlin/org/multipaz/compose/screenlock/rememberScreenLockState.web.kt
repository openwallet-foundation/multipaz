package org.multipaz.compose.screenlock

import androidx.compose.runtime.Composable

private class WebScreenLockState(): ScreenLockState {

    override val hasScreenLock = true  // TODO

    override suspend fun launchSettingsPageWithScreenLock() {
        TODO()
    }
}

@Composable
actual fun rememberScreenLockState(): ScreenLockState {
    return WebScreenLockState()
}

actual fun getScreenLockState(): ScreenLockState = WebScreenLockState()
