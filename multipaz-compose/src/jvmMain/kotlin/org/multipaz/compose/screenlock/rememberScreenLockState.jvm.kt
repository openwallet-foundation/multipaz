package org.multipaz.compose.screenlock

import androidx.compose.runtime.Composable

private class JvmScreenLockState : ScreenLockState {
    override val hasScreenLock = true

    override suspend fun launchSettingsPageWithScreenLock() {
    }
}

@Composable
actual fun rememberScreenLockState(): ScreenLockState = JvmScreenLockState()

actual fun getScreenLockState(): ScreenLockState = JvmScreenLockState()
