package org.multipaz.storage.ephemeral

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toBase64Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.math.abs
import kotlin.random.Random

internal class EphemeralStorageTable(
    override val storage: EphemeralStorage,
    spec: StorageTableSpec,
    private val clock: Clock
): BaseStorageTable(spec) {

    private val lock = Mutex()
    private val storedData = storage.storage.getOrPut(spec.name.lowercase()) {
        mutableListOf()
    }
    private var earliestExpiration: Instant = Instant.DISTANT_FUTURE

    override suspend fun get(key: String, partitionId: String?): ByteString? {
        checkPartition(partitionId)
        return lock.withLock {
            val index = storedData.binarySearch(EphemeralStorageItem(partitionId, key))
            if (index < 0) {
                null
            } else {
                val data = storedData[index]
                if (data.expired(clock.now())) null else data.value
            }
        }
    }

    override suspend fun insert(
        key: String?,
        data: ByteString,
        partitionId: String?,
        expiration: Instant
    ): String {
        checkPartition(partitionId)
        checkExpiration(expiration)
        if (key != null) {
            checkKey(key)
        }
        return lock.withLock {
            var index: Int
            var keyToUse = key
            if (keyToUse == null) {
                do {
                    keyToUse = Random.Default.nextBytes(9).toBase64Url()
                    index = storedData.binarySearch(EphemeralStorageItem(partitionId, keyToUse))
                } while (index >= 0)
            } else {
                index = storedData.binarySearch(EphemeralStorageItem(partitionId, keyToUse))
                if (index >= 0) {
                    val item = storedData[index]
                    if (item.expired(clock.now())) {
                        // Stale entry, can be reused
                        updateEarliestExpiration(expiration)
                        item.expiration = expiration
                        item.value = data
                        return@withLock keyToUse
                    }
                    throw KeyExistsStorageException(
                        "Record with ${recordDescription(key, partitionId)} already exists"
                    )
                }
            }
            check(index < 0)
            updateEarliestExpiration(expiration)
            storedData.add(-index - 1, EphemeralStorageItem(partitionId, keyToUse!!, data, expiration))
            keyToUse
        }
    }

    override suspend fun update(
        key: String,
        data: ByteString,
        partitionId: String?,
        expiration: Instant?
    ) {
        checkPartition(partitionId)
        if (expiration != null) {
            checkExpiration(expiration)
        }
        lock.withLock {
            val index = storedData.binarySearch(EphemeralStorageItem(partitionId, key))
            if (index < 0) {
                throw NoRecordStorageException(
                    "No record with ${recordDescription(key, partitionId)}")
            }
            val item = storedData[index]
            if (item.expired(clock.now())) {
                throw NoRecordStorageException(
                    "No record with ${recordDescription(key, partitionId)} (expired)")
            }
            item.value = data
            if (expiration != null) {
                updateEarliestExpiration(expiration)
                item.expiration = expiration
            }
        }
    }

    override suspend fun delete(key: String, partitionId: String?): Boolean {
        checkPartition(partitionId)
        return lock.withLock {
            val index = storedData.binarySearch(EphemeralStorageItem(partitionId, key))
            if (index < 0 || storedData[index].expired(clock.now())) {
                false
            } else {
                storedData.removeAt(index)
                true
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            storedData.clear()
        }
    }

    override suspend fun deletePartition(partitionId: String) {
        checkPartition(partitionId)
        lock.withLock {
            var index = storedData.binarySearch(EphemeralStorageItem(partitionId, ""))
            if (index < 0) {
                index = -(index + 1)
            }
            val start = index
            while (index < storedData.size && storedData[index].partitionId == partitionId) {
                index++
            }
            storedData.subList(start, index).clear()
        }
    }

    override suspend fun enumerate(
        partitionId: String?,
        afterKey: String?,
        limit: Int
    ) = enumerateImpl(partitionId, afterKey, limit) { it.key }

    override suspend fun enumerateWithData(
        partitionId: String?,
        afterKey: String?,
        limit: Int
    ) = enumerateImpl(partitionId, afterKey, limit) { Pair(it.key, it.value) }

    private suspend fun<T> enumerateImpl(
        partitionId: String?,
        afterKey: String?,
        limit: Int,
        extractor: (EphemeralStorageItem) -> T
    ): List<T> {
        checkPartition(partitionId)
        checkLimit(limit)
        if (limit == 0) {
            return listOf()
        }
        return lock.withLock {
            var index = if (afterKey == null) {
                val spot = storedData.binarySearch(EphemeralStorageItem(partitionId, ""))
                if (spot > 0) spot else -(spot + 1)
            } else {
                abs(storedData.binarySearch(EphemeralStorageItem(partitionId, afterKey)) + 1)
            }
            val now = clock.now()
            val results = mutableListOf<T>()
            while (results.size < limit && index < storedData.size) {
                val data = storedData[index]
                if (data.partitionId != partitionId) {
                    break
                }
                if (!data.expired(now)) {
                    results.add(extractor(data))
                }
                index++
            }
            results.toList()
        }
    }

    private fun updateEarliestExpiration(expiration: Instant) {
        if (earliestExpiration > expiration) {
            earliestExpiration = expiration
        }
    }

    override suspend fun purgeExpired() {
        if (!spec.supportExpiration) {
            throw IllegalStateException("This table does not support expiration")
        }
        lock.withLock {
            val now = clock.now()
            if (earliestExpiration < now) {
                earliestExpiration = Instant.DISTANT_FUTURE
                val unexpired = mutableListOf<EphemeralStorageItem>()
                for (item in storedData) {
                    if (!item.expired(now)) {
                        updateEarliestExpiration(item.expiration)
                        unexpired.add(item)
                    }
                }
                storedData.clear()
                storedData.addAll(unexpired)
            }
        }
    }

    internal suspend fun serialize(out: ByteStringBuilder) {
        Bstr(spec.encodeToByteString().toByteArray()).encode(out)
        val tableData = lock.withLock {
            storedData.map { item -> item.toDataItem() }
        }
        CborArray(tableData.toMutableList()).encode(out)
    }

    companion object {
        val EMPTY = ByteString()

        internal fun deserialize(
            storage: EphemeralStorage,
            clock: Clock,
            input: ByteArray,
            offset: Int
        ): Pair<Int, EphemeralStorageTable> {
            val (offset1, specData) = Cbor.decode(input, offset)
            val spec = StorageTableSpec.decodeByteString(ByteString(specData.asBstr))
            val (offset2, tableData) = Cbor.decode(input, offset1)
            val table = EphemeralStorageTable(storage, spec, clock)
            for (itemData in tableData.asArray) {
                val item = EphemeralStorageItem.fromDataItem(itemData)
                if (table.earliestExpiration > item.expiration) {
                    table.earliestExpiration = item.expiration
                }
                table.storedData.add(item)
            }
            return Pair(offset2, table)
        }
    }
}