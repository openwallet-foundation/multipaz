package org.multipaz.compose.screenlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private object JvmScreenLockState : ScreenLockState {
    override val hasScreenLock = false

    override suspend fun launchSettingsPageWithScreenLock() {
    }
}

@Composable
actual fun rememberScreenLockState(): ScreenLockState = remember { JvmScreenLockState }

actual fun getScreenLockState(): ScreenLockState = JvmScreenLockState
