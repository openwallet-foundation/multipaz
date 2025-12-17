package org.multipaz.rpc

import kotlinx.coroutines.currentCoroutineContext
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import java.util.WeakHashMap
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * Lazily create and memoize an object that is defined by the given class and a key.
 *
 * If/when configuration or resource objects change in the environment, object is created
 * anew. Stale copies are (eventually) deallocated.
 *
 * This method is thread-safe. The same object is returned for all threads, however, when the
 * object is being created, multiple copies might be created if there is a race condition.
 */
suspend fun<ResourceT : Any> BackendEnvironment.cache(
    clazz: KClass<ResourceT>,
    key: Any = "",
    factory: suspend (Configuration, Resources) -> ResourceT
): ResourceT {
    val configuration = getInterface(Configuration::class)!!
    val resources = getInterface(Resources::class)!!
    return cache
        .computeIfAbsent(configuration) {
            WeakHashMap<Resources, EnvironmentCache>()
        }
        .computeIfAbsent(resources) {
            EnvironmentCache()
        }
        .obtain(configuration, resources, clazz, key, factory)
}

suspend fun<ResourceT : Any> BackendEnvironment.Companion.cache(
    clazz: KClass<ResourceT>,
    key: Any = "",
    factory: suspend (Configuration, Resources) -> ResourceT): ResourceT =
        get(currentCoroutineContext()).cache(clazz, key, factory)

private val cache = WeakHashMap<Configuration, WeakHashMap<Resources, EnvironmentCache>>()

private class EnvironmentCache {
    val map = mutableMapOf<KClass<out Any>, MutableMap<Any, Any>>()

    suspend fun<ResourceT : Any> obtain(
        configuration: Configuration,
        resources: Resources,
        clazz: KClass<ResourceT>,
        key: Any,
        factory: suspend (Configuration, Resources) -> ResourceT
    ): ResourceT {
        synchronized(map) {
            val submap = map[clazz]
            if (submap != null) {
                val cached = submap[key]
                if (cached != null) {
                    return clazz.cast(cached)
                }
            }
        }
        val resource = factory(configuration, resources)
        synchronized(map) {
            (map.computeIfAbsent(clazz) { mutableMapOf() })[key] = resource
        }
        return resource
    }
}