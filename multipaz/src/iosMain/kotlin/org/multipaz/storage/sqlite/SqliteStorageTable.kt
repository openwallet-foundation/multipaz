package org.multipaz.storage.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.base.SqlStatementMaker
import org.multipaz.util.toBase64Url
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Crypto

class SqliteStorageTable(
    override val storage: SqliteStorage,
    spec: StorageTableSpec
): BaseStorageTable(spec) {
    private var sql = SqlStatementMaker(
        spec = spec,
        textType = "TEXT",
        blobType = "BLOB",
        longType = "INTEGER",
        // NB: SQLiteConnection API without RETURNING clause support lacks a way to faithfully
        // implement some operations atomically. RETURNING clause is not currently supported
        // when using Android (non-bundled) driver, so using this implementation on Android would
        // require bundled driver. AndroidStorage implementation (which uses non-multiplatform
        // sqlite APIs) is probably a better alternative.
        useReturningClause = true,
        collationCharset = null
    )

    internal suspend fun init() {
        storage.withConnection { connection ->
            connection.execSQL(sql.createTableStatement)
        }
    }

    override suspend fun get(key: String, partitionId: String?): ByteString? {
        checkPartition(partitionId)
        return storage.withConnection { connection ->
            connection.prepare(sql.getStatement).use { statement ->
                statement.bindText(1, key)
                var index = 2
                if (spec.supportPartitions) {
                    statement.bindText(index++, partitionId!!)
                }
                if (spec.supportExpiration) {
                    statement.bindLong(index, storage.clock.now().epochSeconds)
                }
                if (statement.step()) {
                    ByteString(statement.getBlob(0))
                } else {
                    null
                }
            }
        }
    }

    override suspend fun insert(
        key: String?,
        data: ByteString,
        partitionId: String?,
        expiration: Instant
    ): String {
        if (key != null) {
            checkKey(key)
        }
        checkPartition(partitionId)
        checkExpiration(expiration)
        return storage.withConnection { connection ->
            var newKey: String
            if (key != null && spec.supportExpiration) {
                // if there is an entry with this key, but it is expired, it needs to be purged.
                // Purging expired keys does not interfere with operation atomicity
                connection.prepare(sql.purgeExpiredWithIdStatement).use { statement ->
                    statement.bindText(1, key)
                    var index = 2
                    if (spec.supportPartitions) {
                        statement.bindText(index++, partitionId!!)
                    }
                    statement.bindLong(index, storage.clock.now().epochSeconds)
                    statement.step()
                }
            }
            var done = false
            // Loop until the key we generated is unique (if key is not null, we only loop once).
            do {
                newKey = key ?: Crypto.secureRandom.nextBytes(storage.keySize).toBase64Url()
                connection.prepare(sql.insertStatement).use { statement ->
                    var index = 1
                    if (spec.supportPartitions) {
                        statement.bindText(index++, partitionId!!)
                    }
                    statement.bindText(index++, newKey)
                    if (spec.supportExpiration) {
                        statement.bindLong(index++, expiration.epochSeconds)
                    }
                    statement.bindBlob(index, data.toByteArray())
                    try {
                        // This statement will throw an exception if the key already exists.
                        // Unfortunately, different implementations throw different exceptions.
                        // When key is null (i.e. a unique one must be generated), retry, otherwise,
                        // fail.
                        statement.step()
                        done = true
                    } catch (err: SQLiteException) {  /* apple */
                        // nothing
                    } catch (err: RuntimeException) {
                        val errorName = err::class.simpleName
                        if (errorName != "SQLiteConstraintException" /* android */
                            && errorName != "SQLException" /* bundled */
                            ) {
                            throw err
                        }
                    }
                    if (!done && key != null) {
                        throw KeyExistsStorageException(
                            "Record with ${recordDescription(key, partitionId)} already exists"
                        )
                    }
                }
            } while(!done)
            newKey
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
        storage.withConnection { connection ->
            val nowSeconds = storage.clock.now().epochSeconds
            val committed = connection.prepare(
                if (expiration == null) {
                    sql.updateStatement
                } else {
                    sql.updateWithExpirationStatement
                }
            ).use { statement ->
                statement.bindBlob(1, data.toByteArray())
                var index = 2
                if (expiration != null) {
                    statement.bindLong(index++, expiration.epochSeconds)
                }
                statement.bindText(index++, key)
                if (spec.supportPartitions) {
                    statement.bindText(index++, partitionId!!)
                }
                if (spec.supportExpiration) {
                    statement.bindLong(index, nowSeconds)
                }
                statement.step()
            }
            if (!committed) {
                throw NoRecordStorageException(
                    "No record with ${recordDescription(key, partitionId)}")
            }
        }
    }

    override suspend fun delete(key: String, partitionId: String?): Boolean {
        checkPartition(partitionId)
        return storage.withConnection { connection ->
            val nowSeconds = storage.clock.now().epochSeconds
            connection.prepare(sql.deleteStatement).use { statement ->
                statement.bindText(1, key)
                var index = 2
                if (spec.supportPartitions) {
                    statement.bindText(index++, partitionId!!)
                }
                if (spec.supportExpiration) {
                    statement.bindLong(index, nowSeconds)
                }
                statement.step()
            }
        }
    }

    override suspend fun deleteAll() {
        return storage.withConnection { connection ->
            connection.prepare(sql.deleteAllStatement).use { statement ->
                statement.step()
            }
        }
    }

    override suspend fun deletePartition(partitionId: String) {
        return storage.withConnection { connection ->
            checkPartition(partitionId)
            connection.prepare(sql.deleteAllInPartitionStatement).use { statement ->
                statement.bindText(1, partitionId)
                statement.step()
            }
        }
    }


    override suspend fun enumerate(
        partitionId: String?,
        afterKey: String?,
        limit: Int
    ): List<String> {
        checkPartition(partitionId)
        checkLimit(limit)
        if (limit == 0) {
            return listOf()
        }
        return storage.withConnection { connection ->
            connection.prepare(
                if (limit < Int.MAX_VALUE) {
                    sql.enumerateWithLimitStatement(withData = false)
                } else {
                    sql.enumerateStatement(withData = false)
                }
            ).use { statement ->
                var index = 1
                statement.bindText(index++, afterKey ?: "")
                if (spec.supportPartitions) {
                    statement.bindText(index++, partitionId!!)
                }
                if (spec.supportExpiration) {
                    statement.bindLong(index++, storage.clock.now().epochSeconds)
                }
                if (limit < Int.MAX_VALUE) {
                    statement.bindInt(index, limit)
                }
                val list = mutableListOf<String>()
                while (statement.step()) {
                    list.add(statement.getText(0))
                }
                list
            }
        }
    }

    override suspend fun enumerateWithData(
        partitionId: String?,
        afterKey: String?,
        limit: Int
    ): List<Pair<String, ByteString>> {
        checkPartition(partitionId)
        checkLimit(limit)
        if (limit == 0) {
            return listOf()
        }
        return storage.withConnection { connection ->
            connection.prepare(
                if (limit < Int.MAX_VALUE) {
                    sql.enumerateWithLimitStatement(withData = true)
                } else {
                    sql.enumerateStatement(withData = true)
                }
            ).use { statement ->
                var index = 1
                statement.bindText(index++, afterKey ?: "")
                if (spec.supportPartitions) {
                    statement.bindText(index++, partitionId!!)
                }
                if (spec.supportExpiration) {
                    statement.bindLong(index++, storage.clock.now().epochSeconds)
                }
                if (limit < Int.MAX_VALUE) {
                    statement.bindInt(index, limit)
                }
                val list = mutableListOf<Pair<String, ByteString>>()
                while (statement.step()) {
                    list.add(Pair(statement.getText(0), ByteString(statement.getBlob(1))))
                }
                list
            }
        }
    }

    override suspend fun purgeExpired() {
        return storage.withConnection { connection ->
            connection.prepare(sql.purgeExpiredStatement).use { statement ->
                statement.bindLong(1, storage.clock.now().epochSeconds)
                statement.step()
            }
        }
    }
}