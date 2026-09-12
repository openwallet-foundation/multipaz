package org.multipaz.crypto

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal actual class KeyDisposer private constructor(
    private val ref: KeyPhantomReference
) {
    actual fun dispose() {
        ref.clear()
        ref.action = null
        activeReferences.remove(ref)
    }

    actual companion object {
        private val queue = ReferenceQueue<Any>()
        private val activeReferences = Collections.newSetFromMap(ConcurrentHashMap<KeyPhantomReference, Boolean>())

        init {
            Thread({
                while (true) {
                    try {
                        val ref = queue.remove() as? KeyPhantomReference ?: continue
                        activeReferences.remove(ref)
                        ref.action?.invoke()
                        ref.action = null
                    } catch (e: InterruptedException) {
                        break
                    } catch (t: Throwable) {
                        // Suppress background errors
                    }
                }
            }, "MultipazKeyDisposerDaemon").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        actual fun register(target: Any, action: () -> Unit): KeyDisposer {
            val ref = KeyPhantomReference(target, action, queue)
            activeReferences.add(ref)
            return KeyDisposer(ref)
        }
    }
}

internal class KeyPhantomReference(
    referent: Any,
    var action: (() -> Unit)?,
    queue: ReferenceQueue<Any>
) : PhantomReference<Any>(referent, queue)
