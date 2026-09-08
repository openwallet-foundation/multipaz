package org.multipaz.storage.ios

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.sqlite.SqliteStorage
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Implementation of [Storage] for iOS platform.
 *
 * @param storageFileUrl a URL with the path to the database file.
 * @param excludeFromBackup if true, the database file will be excluded from backup.
 * @param busyTimeout timeout to wait for SQLite database locks before failing. Defaults to 5 seconds.
 * @param clock clock to use for timestamps and expiration. Defaults to [Clock.System].
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class IosStorage(
    private val storageFileUrl: NSURL,
    private val excludeFromBackup: Boolean = true,
    busyTimeout: Duration = 5.seconds,
    clock: Clock = Clock.System
): SqliteStorage(
    connection = getConnection(storageFileUrl, excludeFromBackup),
    clock = clock,
    // Native sqlite crashes when used with Dispatchers.IO.
    coroutineContext = newSingleThreadContext("DB"),
    busyTimeout = busyTimeout
) {
    /**
     * Constructor for backwards compatibility with Swift and Objective-C callers
     * who do not specify [busyTimeout] or [clock].
     */
    constructor(
        storageFileUrl: NSURL,
        excludeFromBackup: Boolean
    ) : this(
        storageFileUrl = storageFileUrl,
        excludeFromBackup = excludeFromBackup,
        busyTimeout = 5.seconds,
        clock = Clock.System
    )

    /**
     * Constructor allowing Swift and Objective-C callers to specify a busy timeout in milliseconds.
     */
    constructor(
        storageFileUrl: NSURL,
        excludeFromBackup: Boolean,
        busyTimeoutMs: Long
    ) : this(
        storageFileUrl = storageFileUrl,
        excludeFromBackup = excludeFromBackup,
        busyTimeout = busyTimeoutMs.milliseconds,
        clock = Clock.System
    )

    companion object {
        private fun getConnection(
            storageFileUrl: NSURL,
            excludeFromBackup: Boolean = true
        ): SQLiteConnection {
            if (excludeFromBackup) {
                storageFileUrl.setResourceValue(
                    value = true,
                    forKey = NSURLIsExcludedFromBackupKey,
                    error = null,
                )
            }
            return NativeSQLiteDriver().open(storageFileUrl.path!!)
        }
    }
}
