package org.multipaz.crypto

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

internal class CleanerState(var action: (() -> Unit)?)

@OptIn(ExperimentalNativeApi::class)
internal actual class KeyDisposer private constructor(
    private val state: CleanerState,
    @Suppress("unused")
    private val cleaner: Cleaner
) {
    actual fun dispose() {
        state.action = null
    }

    actual companion object {
        @OptIn(ExperimentalStdlibApi::class)
        actual fun register(target: Any, action: () -> Unit): KeyDisposer {
            val state = CleanerState(action)
            val cleaner = createCleaner(state) { it.action?.invoke() }
            return KeyDisposer(state, cleaner)
        }
    }
}
