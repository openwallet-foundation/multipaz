package org.multipaz.server.common

import kotlin.reflect.KClass
import org.multipaz.rpc.backend.BackendEnvironment

/**
 * Helper interface to build an instance of [ServerEnvironment]
 */
interface ServerEnvironmentInitializer {
    /**
     * Injects an instance of an interface which later can be obtained using
     * [BackendEnvironment.getInterface].
     *
     * @param clazz class of which [instance] is an instance of, this same class have to be
     *  used as [BackendEnvironment.getInterface] later
     * @param instance value that will be returned by [BackendEnvironment.getInterface]
     */
    fun<T: Any> add(clazz: KClass<T>, instance: T)
}