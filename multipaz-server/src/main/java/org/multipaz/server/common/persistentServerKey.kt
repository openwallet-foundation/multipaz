package org.multipaz.server.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import kotlin.random.Random

/**
 * Loads/creates a random sequence of bytes that can be used as a symmetric key that does not
 * change across server invocations.
 *
 * @param length length of the key in bytes (16 by default)
 * @param name name of the key, distinct names will result in distinct keys ("default" by default)
 * @param storage storage interface to use (by default [BackendEnvironment.getInterface] is used
 *     to acquire one)
 * @return persistent key
 */
suspend fun persistentServerKey(
    length: Int = 16,
    name: String = "default",
    storage: Storage? = null
): ByteString {
    val storageToUse = storage ?: BackendEnvironment.getInterface(Storage::class)!!
    val table = storageToUse.getTable(serverKeyTableSpec)
    return serverKeyLock.withLock {
        table.get(name) ?:
            ByteString(Random.nextBytes(length)).also {
                table.insert(key = name, data = it)
            }
    }
}

private val serverKeyLock = Mutex()

private val serverKeyTableSpec = StorageTableSpec(
    name = "ServerKey",
    supportPartitions = false,
    supportExpiration = false
)
