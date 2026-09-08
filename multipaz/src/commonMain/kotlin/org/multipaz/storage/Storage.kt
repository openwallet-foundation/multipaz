package org.multipaz.storage

/**
 * Storage (in most cases persistent) that holds data items. Collection of items are organized
 * in named [StorageTable]s.
 *
 * ## Concurrency and Multiple Instances
 *
 * It is safe to use multiple [Storage] instances that reference the same underlying database
 * concurrently, whether within the same process or across different processes (for example, a mobile
 * application and an app extension sharing an App Group container).
 *
 * Implementations provide concurrency guarantees through the underlying database engine:
 * - **Atomicity:** Individual operations on [StorageTable] (such as `insert`, `update`, `delete`,
 *   and `get`) are atomic.
 * - **Contention Handling:** When multiple connections or processes attempt to access or modify
 *   the database simultaneously, implementations configure appropriate lock wait mechanisms
 *   (e.g., SQLite's `busy_timeout` on mobile platforms, transaction queues in IndexedDB, or MVCC
 *   in relational databases). Operations will automatically wait for concurrent locks to be released
 *   rather than immediately failing with contention errors. If contention persists longer than
 *   the configured timeout, the operation will fail with an exception.
 */
interface Storage {
    /**
     * Get the table with specific name and features.
     *
     * In order to avoid situation where several parts of the app define a table with the
     * same name, this method throws [IllegalArgumentException] when there are multiple
     * [StorageTableSpec] objects that define a table with the same name.
     */
    suspend fun getTable(spec: StorageTableSpec): StorageTable

    /**
     * Reclaim the storage occupied by expired entries across all tables in this [Storage]
     * object (even if these tables were never accessed using [getTable] in this
     * session).
     */
    suspend fun purgeExpired()
}