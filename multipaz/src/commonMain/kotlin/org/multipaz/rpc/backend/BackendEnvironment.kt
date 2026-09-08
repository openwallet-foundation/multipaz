package org.multipaz.rpc.backend

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Interface to access server-provided interfaces from within the RPC methods.
 *
 * An instance of this class is added to coroutine context by RPC dispatcher and can be extracted
 * using [BackendEnvironment.get] method.
 */
interface BackendEnvironment: CoroutineContext.Element {
    object Key: CoroutineContext.Key<BackendEnvironment>

    override val key: CoroutineContext.Key<BackendEnvironment>
        get() = Key

    fun<T: Any> getInterface(clazz: KClass<T>): T?

    companion object {
        val EMPTY = object: BackendEnvironment {
            override fun <T : Any> getInterface(clazz: KClass<T>): T? {
                return null
            }
        }

        fun get(coroutineContext: CoroutineContext): BackendEnvironment {
            return coroutineContext[Key] ?: throw BackendNotAvailable()
        }

        suspend fun <T: Any> getInterface(clazz: KClass<T>): T? =
            get(currentCoroutineContext()).getInterface(clazz)
    }
}