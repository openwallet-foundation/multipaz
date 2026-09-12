package org.multipaz.crypto

/**
 * Registers a safety net cleanup callback to be invoked when [target] is garbage-collected.
 */
internal expect class KeyDisposer {
    companion object {
        fun register(target: Any, action: () -> Unit): KeyDisposer
    }

    /**
     * Unregisters the disposer and releases any held resources.
     */
    fun dispose()
}
