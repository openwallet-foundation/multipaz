package org.multipaz.storage.sqlite

import androidx.sqlite.SQLiteConnection
import org.multipaz.storage.Storage
import org.multipaz.storage.base.BaseStorage
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.StorageTableSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.coroutines.CoroutineContext

/**
 * [Storage] implementation based on Kotlin Multiplatform [androidx.sqlite.SQLiteConnection] API.
 *
 * One limitation of [androidx.sqlite.SQLiteConnection] APIs is that there is no way to get result from
 * `UPDATE` and `DELETE` SQL statements. This can be worked around either using
 * SQLite-specific `RETURNING` clause (without breaking atomicity) or by additional
 * `SELECT` statements (this does break atomicity).
 *
 * Note: currently we use this implementation only for iOS as there are multiple problems
 * using this code on Android:
 * - required androidx.sqlite library version (2.5.0-alpha12) conflicts with some
 *   other commonly used Android libraries.
 * - implementation supplied by AndroidSQLiteDriver lacks support for SQLite-specific
 *   `RETURNING` clause and thus it is not possible to guarantee truly atomic operations
 *   (most notably insertions with unique keys and correct return value from deletions).
 */
open class SqliteStorage(
    private val connection: SQLiteConnection,
    clock: Clock = Clock.System,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
    internal val keySize: Int = 9
): BaseStorage(clock) {
    override suspend fun createTable(tableSpec: StorageTableSpec): BaseStorageTable {
        val table = SqliteStorageTable(this, tableSpec)
        table.init()
        return table
    }

    internal suspend fun<T> withConnection(
        block: suspend (connection: SQLiteConnection) -> T
    ): T {
        return withContext(coroutineContext) {
            block(connection)
        }
    }
}