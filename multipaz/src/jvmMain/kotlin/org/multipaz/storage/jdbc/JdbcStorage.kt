package org.multipaz.storage.jdbc

import kotlinx.coroutines.CancellationException
import org.multipaz.storage.base.BaseStorage
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.base.SqlStatementMaker
import kotlin.time.Clock
import kotlin.time.Instant
import java.sql.Connection
import java.sql.DriverManager
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes

class JdbcStorage(
    private val jdbc: String,
    private val user: String = "",
    private val password: String = "",
    clock: Clock = Clock.System,
    private val executor: Executor = Executors.newFixedThreadPool(4),
    internal val keySize: Int = 12 /* exposed for testing only */
): BaseStorage(clock) {
    private val connectionPool = ArrayDeque<ConnectionPoolEntry>()

    override suspend fun createTable(tableSpec: StorageTableSpec): BaseStorageTable {
        val sql = if (jdbc.startsWith("jdbc:mysql:")) {
            SqlStatementMaker(
                spec = tableSpec,
                textType = "VARCHAR($MAX_KEY_SIZE)",
                blobType = "LONGBLOB",  // avoids 64k limit
                longType = "BIGINT",
                useReturningClause = false,
                collationCharset = "latin1_bin"
            )
        } else if (jdbc.startsWith("jdbc:postgresql:")) {
            SqlStatementMaker(
                spec = tableSpec,
                textType = "TEXT",
                blobType = "BYTEA",
                longType = "BIGINT",
                useReturningClause = false,
                collationCharset = null
            )
        } else {
            SqlStatementMaker(
                spec = tableSpec,
                textType = "VARCHAR($MAX_KEY_SIZE)",
                blobType = "BLOB",
                longType = "BIGINT",
                useReturningClause = false,
                collationCharset = null
            )
        }
        val table = JdbcStorageTable(this, sql)
        table.init()
        return table
    }

    internal suspend fun<T> withConnection(block: (connection: Connection) -> T): T {
        return suspendCoroutine { continuation ->
            executor.execute {
                val staleConnections = mutableListOf<Connection>()
                // only real clock makes sense here
                val connectionExpiration = Clock.System.now() - MAX_CONNECTION_LIFE
                val connection = synchronized(connectionPool) {
                    while (connectionPool.isNotEmpty()) {
                        val poolEntry = connectionPool.removeFirst()
                        if (poolEntry.timeLastUsed > connectionExpiration) {
                            return@synchronized poolEntry.connection
                        }
                        staleConnections.add(poolEntry.connection)
                    }
                    null
                } ?: DriverManager.getConnection(jdbc, user, password)
                try {
                    val result = block(connection)
                    continuation.resume(result)
                    synchronized(connectionPool) {
                        connectionPool.add(
                            ConnectionPoolEntry(
                                connection = connection,
                                timeLastUsed = Clock.System.now()
                            )
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    continuation.resumeWithException(error)
                    // Close after exceptions instead of returning to the pool
                    connection.close()
                }
                for (staleConnection in staleConnections) {
                    try {
                        staleConnection.close()
                    } catch (err: Exception) {
                        if (err is CancellationException) throw err
                        // ignore all errors
                    }
                }
            }
        }
    }

    class ConnectionPoolEntry(val connection: Connection, val timeLastUsed: Instant)

    companion object {
        val MAX_CONNECTION_LIFE = 3.minutes
    }
}