package org.multipaz.storage.jdbc

import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.base.SqlStatementMaker
import org.multipaz.util.toBase64Url
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import java.sql.SQLException
import org.multipaz.crypto.Crypto

class JdbcStorageTable(
    override val storage: JdbcStorage,
    private val sql: SqlStatementMaker
): BaseStorageTable(sql.spec) {
    internal suspend fun init() {
        storage.withConnection { connection ->
            connection.createStatement().execute(sql.createTableStatement)
        }
    }

    override suspend fun get(key: String, partitionId: String?): ByteString? {
        checkPartition(partitionId)
        return storage.withConnection { connection ->
            val statement = connection.prepareStatement(sql.getStatement)
            var index = 1
            statement.setString(index++, key)
            if (spec.supportPartitions) {
                statement.setString(index++, partitionId!!)
            }
            if (spec.supportExpiration) {
                statement.setLong(index, storage.clock.now().epochSeconds)
            }
            val resultSet = statement.executeQuery()
            if (resultSet.next()) {
                val bytes = resultSet.getBytes(1)
                ByteString(bytes)
            } else {
                null
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
        return storage.withConnection { connection ->
            var newKey: String
            if (key != null && spec.supportExpiration) {
                // if there is an entry with this key, but it is expired, it needs to be purged
                // purging expired keys does not interfere with operation atomicity
                val purge = connection.prepareStatement(sql.purgeExpiredWithIdStatement)
                purge.setString(1, key)
                var index = 2
                if (spec.supportPartitions) {
                    purge.setString(index++, partitionId)
                }
                purge.setLong(index, storage.clock.now().epochSeconds)
                purge.executeUpdate()
            }
            var tries = 0
            // Loop until the key we generated is unique (if key is not null, we only loop once).
            while (true) {
                newKey = key ?: Crypto.secureRandom.nextBytes(storage.keySize).toBase64Url()
                val values = StringBuilder("?, ?")
                if (spec.supportPartitions) {
                    values.append(", ?")
                }
                if (spec.supportExpiration) {
                    values.append(", ?")
                }
                val statement = connection.prepareStatement(sql.insertStatement)
                var index = 1
                if (spec.supportPartitions) {
                    statement.setString(index++, partitionId!!)
                }
                statement.setString(index++, newKey)
                if (spec.supportExpiration) {
                    statement.setLong(index++, expiration.epochSeconds)
                }
                statement.setBytes(index, data.toByteArray())
                val count = try {
                    // This statement will throw an exception if the key already exists.
                    // When key is null (i.e. a unique one must be generated), retry, otherwise,
                    // fail.
                    statement.executeUpdate()
                } catch (err: SQLException) {
                    // NB: we are using a very generic exception to detect key collision error
                    // (every jdbc driver seem to have its own flavor, unfortunately).
                    // After enough tries, we should conclude that the problem is likely to
                    // be something else, otherwise this will become an infinite loop.
                    if (++tries > 21) {
                        throw err
                    }
                    if (key == null) {
                        continue
                    } else {
                        throw KeyExistsStorageException(
                            "Record with ${recordDescription(key, partitionId)} already exists"
                        )
                    }
                }
                if (count != 1) {
                    throw IllegalStateException(
                        "Expected SQL INSERT statement to return 1, got $count")
                }
                statement.close()
                break
            }
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
            val statement = connection.prepareStatement(
                if (expiration != null) {
                    sql.updateWithExpirationStatement
                } else {
                    sql.updateStatement
                }
            )
            statement.setBytes(1, data.toByteArray())
            var index = 2
            if (expiration != null) {
                statement.setLong(index++, expiration.epochSeconds)
            }
            statement.setString(index++, key)
            if (spec.supportPartitions) {
                statement.setString(index++, partitionId!!)
            }
            if (spec.supportExpiration) {
                statement.setLong(index, storage.clock.now().epochSeconds)
            }
            val count = statement.executeUpdate()
            if (count != 1) {
                throw NoRecordStorageException(
                    "No record with ${recordDescription(key, partitionId)}")
            }
        }
    }

    override suspend fun delete(key: String, partitionId: String?): Boolean {
        checkPartition(partitionId)
        return storage.withConnection { connection ->
            val statement = connection.prepareStatement(sql.deleteStatement)
            statement.setString(1, key)
            var index = 2
            if (spec.supportPartitions) {
                statement.setString(index++, partitionId!!)
            }
            if (spec.supportExpiration) {
                statement.setLong(index, storage.clock.now().epochSeconds)
            }
            val count = statement.executeUpdate()
            count > 0
        }
    }

    override suspend fun deleteAll() {
        return storage.withConnection { connection ->
            connection.prepareStatement(sql.deleteAllStatement).executeUpdate()
        }
    }

    override suspend fun deletePartition(partitionId: String) {
        checkPartition(partitionId)
        return storage.withConnection { connection ->
            val statement = connection.prepareStatement(sql.deleteAllInPartitionStatement)
            statement.setString(1, partitionId)
            statement.executeUpdate()
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
            val statement = connection.prepareStatement(
                if (limit < Int.MAX_VALUE) {
                    sql.enumerateWithLimitStatement(false)
                } else {
                    sql.enumerateStatement(false)
                }
            )
            var index = 1
            statement.setString(index++, afterKey ?: "")
            if (spec.supportPartitions) {
                statement.setString(index++, partitionId!!)
            }
            if (spec.supportExpiration) {
                statement.setLong(index++, storage.clock.now().epochSeconds)
            }
            if (limit < Int.MAX_VALUE) {
                statement.setInt(index, limit)
            }
            val resultSet = statement.executeQuery()
            val list = mutableListOf<String>()
            while (resultSet.next()) {
                list.add(resultSet.getString(1))
            }
            list
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
            val statement = connection.prepareStatement(
                if (limit < Int.MAX_VALUE) {
                    sql.enumerateWithLimitStatement(true)
                } else {
                    sql.enumerateStatement(true)
                }
            )
            var index = 1
            statement.setString(index++, afterKey ?: "")
            if (spec.supportPartitions) {
                statement.setString(index++, partitionId!!)
            }
            if (spec.supportExpiration) {
                statement.setLong(index++, storage.clock.now().epochSeconds)
            }
            if (limit < Int.MAX_VALUE) {
                statement.setInt(index, limit)
            }
            val resultSet = statement.executeQuery()
            val list = mutableListOf<Pair<String, ByteString>>()
            while (resultSet.next()) {
                list.add(Pair(resultSet.getString(1), ByteString(resultSet.getBytes(2))))
            }
            list
        }
    }

    override suspend fun purgeExpired() {
        if (!spec.supportExpiration) {
            throw IllegalStateException("This table does not support expiration")
        }
        storage.withConnection { connection ->
            val purge = connection.prepareStatement(sql.purgeExpiredStatement)
            purge.setLong(1, storage.clock.now().epochSeconds)
            purge.executeUpdate()
        }
    }
}