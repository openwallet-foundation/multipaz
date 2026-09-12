package org.multipaz.crypto

internal actual class KeyDisposer private constructor(
    private var action: (() -> Unit)?
) {
    actual fun dispose() {
        action = null
    }

    actual companion object {
        actual fun register(target: Any, action: () -> Unit): KeyDisposer {
            return KeyDisposer(action)
        }
    }
}
