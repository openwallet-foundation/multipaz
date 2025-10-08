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

/**
 * Implementation of [Storage] for iOS platform.
 *
 * @param storageFileUrl a URL with the path to the database file.
 * @param excludeFromBackup if true, the database file will be excluded from backup.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class IosStorage(
    private val storageFileUrl: NSURL,
    private val excludeFromBackup: Boolean = true
): SqliteStorage(
    connection = getConnection(storageFileUrl, excludeFromBackup),
    // Native sqlite crashes when used with Dispatchers.IO.
    coroutineContext = newSingleThreadContext("DB")
) {
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