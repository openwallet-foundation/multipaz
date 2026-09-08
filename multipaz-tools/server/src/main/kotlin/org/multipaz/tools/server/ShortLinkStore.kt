@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.multipaz.tools.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.jdbc.JdbcStorage
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Data record representing a stored short link.
 *
 * @property id unique 6-character short code identifier
 * @property path the target URL path starting with '/'
 * @property createdAt instant when the link was created
 * @property lastAccessedAt instant when the link was last accessed
 */
@Serializable
data class ShortLinkRecord(
    val id: String,
    val path: String,
    val createdAt: Instant,
    var lastAccessedAt: Instant
)

/**
 * Storage service for creating and resolving short links.
 */
class ShortLinkStore private constructor(
    private val table: StorageTable
) {
    private val mutex = Mutex()
    private val recordsMap = mutableMapOf<String, ShortLinkRecord>()
    private val pathToIdMap = mutableMapOf<String, String>()
    private val lastCreationPerIp = mutableMapOf<String, Instant>()

    private suspend fun loadFromStorage() {
        val records = table.enumerateWithData()
        for ((id, data) in records) {
            val record = json.decodeFromString<ShortLinkRecord>(data.toByteArray().decodeToString())
            recordsMap[id] = record
            pathToIdMap[record.path] = record.id
        }
    }

    /**
     * Creates a new short link for the given URL path.
     *
     * @param path the target URL path starting with '/'
     * @param clientIp the client IP address for rate limiting
     * @return the 6-character short code assigned to the link
     * @throws IllegalArgumentException if the path is invalid or exceeds 256 kB
     * @throws RateLimitExceededException if rate limit of 1 link per 10 seconds per IP is exceeded
     */
    suspend fun createShortLink(path: String, clientIp: String): String = mutex.withLock {
        require(path.startsWith("/")) { "Invalid path: must start with '/'" }
        require(path.length <= MAX_PATH_LENGTH) { "Path exceeds maximum allowed size of 256 kB" }

        val now = Clock.System.now()

        // Rate limiting check: 1 link per 10 seconds per IP
        val lastTime = lastCreationPerIp[clientIp]
        if (lastTime != null && (now - lastTime) < RATE_LIMIT_DURATION) {
            val remainingSec = (RATE_LIMIT_DURATION - (now - lastTime)).inWholeSeconds + 1
            throw RateLimitExceededException(
                "Rate limit exceeded. Please wait $remainingSec seconds before creating another link."
            )
        }

        // Deduplication check: if path already exists, update access time and return ID
        val existingId = pathToIdMap[path]
        if (existingId != null) {
            val record = recordsMap[existingId]
            if (record != null) {
                record.lastAccessedAt = now
                lastCreationPerIp[clientIp] = now
                val bytes = ByteString(json.encodeToString(record).toByteArray())
                table.update(key = record.id, data = bytes)
                return record.id
            }
        }

        // Generate unique short ID (6 chars)
        var newId: String
        do {
            newId = generateShortCode(6)
        } while (recordsMap.containsKey(newId))

        val newRecord = ShortLinkRecord(
            id = newId,
            path = path,
            createdAt = now,
            lastAccessedAt = now
        )

        recordsMap[newId] = newRecord
        pathToIdMap[path] = newId
        lastCreationPerIp[clientIp] = now

        val bytes = ByteString(json.encodeToString(newRecord).toByteArray())
        table.insert(key = newId, data = bytes)

        return newId
    }

    /**
     * Retrieves the short link record for the given 6-character short code.
     *
     * @param id the short code identifier
     * @return the [ShortLinkRecord] if found, or null if non-existent
     */
    suspend fun getShortLink(id: String): ShortLinkRecord? = mutex.withLock {
        val record = recordsMap[id] ?: return null
        val now = Clock.System.now()

        record.lastAccessedAt = now
        val bytes = ByteString(json.encodeToString(record).toByteArray())
        table.update(key = record.id, data = bytes)
        return record
    }

    companion object {
        private const val MAX_PATH_LENGTH = 256 * 1024 // 256 kB max
        private val RATE_LIMIT_DURATION = 10.seconds

        private const val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        private val tableSpec = StorageTableSpec(
            name = "ShortLinks",
            supportPartitions = false,
            supportExpiration = false
        )

        /**
         * Creates and initializes a [ShortLinkStore] instance using the provided [Storage].
         *
         * @param storage the [Storage] instance to manage persistent short link records
         * @return an initialized [ShortLinkStore] instance
         */
        suspend fun create(storage: Storage): ShortLinkStore {
            val table = storage.getTable(tableSpec)
            val store = ShortLinkStore(table)
            store.loadFromStorage()
            return store
        }

        /**
         * Creates and initializes a [ShortLinkStore] instance using a default SQLite database.
         *
         * @param dbPath path to the SQLite database file
         * @return an initialized [ShortLinkStore] instance
         */
        suspend fun createDefault(dbPath: String = "data/shortlinks.db"): ShortLinkStore {
            val path = Path(dbPath)
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            val storage = JdbcStorage("jdbc:sqlite:${dbPath}")
            return create(storage)
        }

        private fun generateShortCode(length: Int): String {
            val sb = StringBuilder(length)
            for (i in 0 until length) {
                sb.append(BASE62_CHARS[Random.nextInt(BASE62_CHARS.length)])
            }
            return sb.toString()
        }
    }
}

/**
 * Exception thrown when a client exceeds short link creation rate limits.
 */
class RateLimitExceededException(message: String) : Exception(message)
