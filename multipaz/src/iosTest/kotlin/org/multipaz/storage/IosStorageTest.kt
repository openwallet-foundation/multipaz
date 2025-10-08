package org.multipaz.storage

import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.storage.sqlite.SqliteStorage
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class IosStorageTest {
    @BeforeTest
    fun resetSharedState() {
        TestClock.time = Instant.DISTANT_PAST
    }

    @Test
    fun testSimple() {
        withTable { table ->
            assertEquals(0, table.enumerate().size.toLong())
            assertNull(table.get("foo"))

            val data = "Foobar".encodeToByteString()
            table.insert(data = data, key = "foo")
            assertEquals(table.get("foo"), data)
            assertEquals(1, table.enumerate().size.toLong())
            assertEquals("foo", table.enumerate().iterator().next())
            assertNull(table.get("bar"))

            val data2 = "Buzz".encodeToByteString()
            table.insert(data = data2, key = "bar")
            assertEquals(table.get("bar"), data2)
            assertEquals(2, table.enumerate().size.toLong())
            table.delete("foo")
            assertNull(table.get("foo"))
            assertNotNull(table.get("bar"))
            assertEquals(1, table.enumerate().size.toLong())
            table.delete("bar")
            assertNull(table.get("bar"))
            assertEquals(0, table.enumerate().size.toLong())
        }
    }

    @Test
    fun testGetReturnsCorrectEntry() {
        withTable(supportPartitions = true) { table ->
            table.insert(key = null, "bad1".encodeToByteString(), partitionId = "client0")
            val key = table.insert(key = null, "good".encodeToByteString(), partitionId = "client1")
            table.insert(key = null, "bad2".encodeToByteString(), partitionId = "client1")
            table.insert(key = null, "bad3".encodeToByteString(), partitionId = "client3")
            assertNotEquals("", key)
            val data = table.get(key = key, partitionId = "client1")
            assertEquals("good", data!!.decodeToString())
        }
    }

    @Test
    fun testEnumerate() {
        withTable(supportPartitions = true) { table ->
            (0..19).forEach { i ->
                table.insert(
                    key = null,
                    partitionId = "client0",
                    data = "bad$i".encodeToByteString()
                )
                table.insert(
                    key = null,
                    partitionId = "client2",
                    data = "bad$i".encodeToByteString()
                )
            }
            val valueMap = (0..19).associate { i ->
                val data = "good$i"
                val key = table.insert(
                    key = null,
                    partitionId = "client1",
                    data = data.encodeToByteString()
                )
                Pair(key, data)
            }
            assertEquals(20, valueMap.size)
            val chunk1 = table.enumerate(
                partitionId = "client1",
                limit = 9
            )
            val chunk2WithData = table.enumerateWithData(
                partitionId = "client1",
                afterKey = chunk1.last(),
                limit = 14
            )
            assertEquals(9, chunk1.size)
            assertEquals(11, chunk2WithData.size)
            assertEquals(valueMap.keys.sorted(), chunk1 + chunk2WithData.map { it.first })
            for ((key, value) in valueMap) {
                val data = table.get(partitionId = "client1", key = key)!!.decodeToString()
                assertEquals(value, data)
            }
            for ((key, data) in chunk2WithData) {
                assertEquals(valueMap[key], data.decodeToString())
            }
            assertEquals(listOf(), table.enumerate(partitionId = "client1", limit = 0))
        }
    }

    @Test
    fun testUpdate() {
        withTable(supportPartitions = true) { table ->
            val key = table.insert(
                key = null,
                partitionId = "client1",
                data = "bad".encodeToByteString()
            )
            table.update(key = key, partitionId = "client1", data = "good".encodeToByteString())
            val data = table.get(partitionId = "client1", key = key)
            assertEquals("good", data!!.decodeToString())
        }
    }

    @Test
    fun testUpdateExpiration() {
        withTable(supportPartitions = true, supportExpiration = true) { table ->
            TestClock.time = Instant.parse("2024-12-20T11:35:00Z")
            val key1 = table.insert(
                key = null,
                partitionId = "client1",
                expiration = TestClock.now() + 2.minutes,
                data = "entry1".encodeToByteString()
            )
            val key2 = table.insert(
                key = null,
                partitionId = "client1",
                expiration = TestClock.now() + 2.minutes,
                data = "entry2".encodeToByteString()
            )
            table.update(
                key = key1,
                partitionId = "client1",
                expiration = TestClock.now() + 10.minutes,
                data = "updated1".encodeToByteString()
            )
            TestClock.time += 5.minutes
            assertEquals(
                "updated1".encodeToByteString(),
                table.get(partitionId = "client1", key = key1)
            )
            assertNull(table.get(partitionId = "client1", key = key2))
            assertEquals(listOf(key1), table.enumerate(partitionId = "client1"))
        }
    }

    @Test
    fun testDelete() {
        withTable(supportPartitions = true) { table ->
            val key = table.insert(key = null, partitionId = "client1", data = "data".encodeToByteString())
            assertFalse(table.delete(partitionId = "client0", key = key))
            assertFalse(table.delete(partitionId = "client1", key = "#fake_key"))
            assertTrue(table.delete(partitionId = "client1", key = key))
            val data = table.get(partitionId = "client1", key = key)
            assertNull(data)
        }
    }

    @Test
    fun testExpiration() {
        withStorage { storage ->
            val tableSpec = StorageTableSpec(
                name = "TestExpiration${uniqueSuffix()}",
                supportPartitions = false,
                supportExpiration = true
            )
            val table = storage.getTable(tableSpec)
            TestClock.time = Instant.parse("2024-12-20T11:35:00Z")
            val time3 = TestClock.time + 3.minutes
            val time5 = TestClock.time + 5.minutes
            val time6 = TestClock.time + 6.minutes
            val time8 = TestClock.time + 8.minutes
            val id8 = table.insert(null, "8.minutes".encodeToByteString(), expiration = time8)
            val id3 = table.insert(null, "3.minutes".encodeToByteString(), expiration = time3)
            val id5 = table.insert(null, "5.minutes".encodeToByteString(), expiration = time5)
            assertEquals(setOf(id3, id5, id8), table.enumerate().toSet())
            TestClock.time = time5
            assertEquals(setOf(id5, id8), table.enumerate().toSet())
            table.insert(key = id3, data = "reused id".encodeToByteString(), expiration = time5)
            assertEquals(setOf(id3, id5, id8), table.enumerate().toSet())
            TestClock.time = time6
            assertEquals(setOf(id8), table.enumerate().toSet())
            assertEquals("8.minutes", table.get(id8)!!.decodeToString())
            storage.purgeExpired()
            assertEquals(setOf(id8), table.enumerate().toSet())
        }
    }

    @Test
    fun testExpirationWithPartitions() {
        withStorage { storage ->
            val tableSpec = StorageTableSpec(
                name = "TestExpirationPartitions${uniqueSuffix()}",
                supportPartitions = true,
                supportExpiration = true
            )
            val table = storage.getTable(tableSpec)
            TestClock.time = Instant.parse("2024-12-20T11:35:00Z")
            val id8 = table.insert(null, "8.minutes".encodeToByteString(),
                partitionId = "A", expiration = TestClock.time + 8.minutes)
            val id3 = table.insert(null, "3.minutes".encodeToByteString(),
                partitionId = "B", expiration = TestClock.time + 3.minutes)
            assertEquals(setOf(id8), table.enumerate("A").toSet())
            assertEquals("8.minutes", table.get(id8,"A")!!.decodeToString())
            assertNull(table.get(id8,"B"))
            assertEquals(setOf(id3), table.enumerate("B").toSet())
            assertEquals("3.minutes", table.get(id3,"B")!!.decodeToString())
            assertNull(table.get(id3,"A"))

            TestClock.time += 4.minutes
            assertEquals(setOf(id8), table.enumerate("A").toSet())
            assertEquals("8.minutes", table.get(id8,"A")!!.decodeToString())
            assertNull(table.get(id8,"B"))
            assertEquals(setOf(), table.enumerate("B").toSet())
            assertNull(table.get(id3,"B"))
            assertNull(table.get(id3,"A"))
        }
    }

    @Test
    fun testDuplicateKey() {
        withTable { table ->
            table.insert(key = "foo", "bar".encodeToByteString())
            assertFailsWith(KeyExistsStorageException::class) {
                table.insert(key = "foo", "buz".encodeToByteString())
            }
        }
    }

    @Test
    fun testKeysCaseSensitive() {
        withTable(supportPartitions = true) { table ->
            table.insert(key = "foo", partitionId = "a", data = "bar".encodeToByteString())
            assertEquals("bar".encodeToByteString(), table.get(key = "foo", partitionId = "a"))
            assertNull(table.get(key = "FOO", partitionId = "A"))
            assertNull(table.get(key = "FOO", partitionId = "a"))
            assertNull(table.get(key = "foo", partitionId = "A"))
            assertFailsWith(NoRecordStorageException::class) {
                table.update(key = "FOO", partitionId = "A", data = "buz".encodeToByteString())
            }
            assertFailsWith(NoRecordStorageException::class) {
                table.update(key = "FOO", partitionId = "a", data = "buz".encodeToByteString())
            }
            assertFailsWith(NoRecordStorageException::class) {
                table.update(key = "foo", partitionId = "A", data = "buz".encodeToByteString())
            }
            // Non-duplicates
            table.insert(key = "FOO", partitionId = "A", data = "q".encodeToByteString())
            table.insert(key = "foo", partitionId = "A", data = "r".encodeToByteString())
            table.insert(key = "FOO", partitionId = "a", data = "s".encodeToByteString())
        }
    }

    @Test
    fun testUpdateNonexistent() {
        withTable { table ->
            assertFailsWith(NoRecordStorageException::class) {
                table.update(key = "foo", "buz".encodeToByteString())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testConcurrentInsertion() {
        withTable { table ->
            val result = async(newFixedThreadPoolContext(nThreads = 10, name = "inserter")) {
                (0..99).map { coroutineId ->
                    async {
                        (0..99).associate { dataId ->
                            val value = "$coroutineId:$dataId"
                            val key = table.insert(key = null, value.encodeToByteString())
                            delay(Random.nextLong(2))  // a bit of jitter
                            Pair(key, value)
                        }
                    }
                }
            }
            val allInsertedEntries = mutableMapOf<String, String>()
            result.await().map { insertingJob ->
                insertingJob.await()
            }.forEach { insertedEntries ->
                allInsertedEntries.putAll(insertedEntries)
            }

            assertEquals(allInsertedEntries.keys.toSet(), table.enumerate().toSet())
            for ((key, value) in allInsertedEntries.entries) {
                assertEquals(value, table.get(key)!!.decodeToString())
            }
        }
    }

    @Test
    fun testBasicPersistence() {
        val storageName = "testBasicPersistence"
        val storage1 = createPersistentStorage(storageName, TestClock) ?: return
        val tableSpec = StorageTableSpec("table", false, false)
        runBlocking {
            val table1 = storage1.getTable(tableSpec)
            val key = table1.insert(key = null, data = "Hello, world!".encodeToByteString())
            val storage2 = createPersistentStorage(storageName, TestClock)!!
            val table2 = storage2.getTable(tableSpec)
            assertEquals("Hello, world!", table2.get(key)!!.decodeToString())
        }
    }

    @Test
    fun testSchemaUpdate() {
        val storageName = "testSchemaUpdate"
        val storage1 = createPersistentStorage(storageName, TestClock) ?: return
        val spec1 = StorageTableSpec(
            name = "table",
            supportPartitions = false,
            supportExpiration = false
        )
        val spec1Uppercase = StorageTableSpec(
            name = "TABLE",
            supportPartitions = false,
            supportExpiration = false
        )
        val spec2 = object : StorageTableSpec(
            name = "table",
            supportPartitions = false,
            supportExpiration = false,
            schemaVersion = 1
        ) {
            override suspend fun schemaUpgrade(oldTable: BaseStorageTable) {
                val ids = oldTable.enumerate()
                for (id in ids) {
                    val oldValue = oldTable.get(id)!!.decodeToString()
                    val newValue = oldValue.replace("old", "new")
                    oldTable.update(id, newValue.encodeToByteString())
                }
            }
        }
        runBlocking {
            val table1 = storage1.getTable(spec1)
            val key1 = table1.insert(key = null, data = "old value 1".encodeToByteString())
            val key2 = table1.insert(key = null, data = "old value 2".encodeToByteString())
            val storage2 = createPersistentStorage(storageName, TestClock)!!
            assertFailsWith(IllegalStateException::class) {
                storage2.getTable(spec1Uppercase)
            }
            val storage3 = createPersistentStorage(storageName, TestClock)!!
            val table3 = storage3.getTable(spec2)
            assertEquals("new value 1", table3.get(key1)!!.decodeToString())
            assertEquals("new value 2", table3.get(key2)!!.decodeToString())
            val storage4 = createPersistentStorage(storageName, TestClock)!!
            assertFailsWith(IllegalStateException::class) {
                storage4.getTable(spec1)
            }
        }
    }

    @Test
    fun testTableName() {
        withStorage { storage ->
            storage.getTable(StorageTableSpec("foo", true, true))
            // Duplicate name (
            assertFailsWith(IllegalArgumentException::class) {
                storage.getTable(StorageTableSpec("foo", true, true))
            }
            // Duplicate name (case-insensitive comparison)
            assertFailsWith(IllegalArgumentException::class) {
                storage.getTable(StorageTableSpec("Foo", true, true))
            }
            // Name is not too long
            storage.getTable(StorageTableSpec(LONG_NAME_60, true, true))
            // Name is too long
            assertFailsWith(IllegalArgumentException::class) {
                storage.getTable(StorageTableSpec(LONG_NAME_60 + "X", true, true))
            }
            // Name does not start with a letter
            assertFailsWith(IllegalArgumentException::class) {
                storage.getTable(StorageTableSpec("1", true, true))
            }
            // Name contains illegal character
            assertFailsWith(IllegalArgumentException::class) {
                storage.getTable(StorageTableSpec("foo@", true, true))
            }
        }
    }

    @Test
    fun testKeyValidity() {
        withTable(supportPartitions = true) { table ->
            val longKey = Random.nextBytes(512).toHex()
            val longPartition = Random.nextBytes(512).toHex()
            assertEquals(1024, longKey.length)
            // no error
            table.insert(
                key = longKey,
                partitionId = longPartition,
                data = "data".encodeToByteString()
            )
            // key is too long
            assertFailsWith(IllegalArgumentException::class) {
                table.insert(
                    key = longKey + "X",
                    partitionId = longPartition,
                    data = "data".encodeToByteString()
                )
            }
            // partition is too long
            assertFailsWith(IllegalArgumentException::class) {
                table.insert(
                    key = longKey,
                    partitionId = longPartition + "X",
                    data = "data".encodeToByteString()
                )
            }
        }
    }

    @Test
    fun testLargeData() {
        withTable { table ->
            val data = ByteString(Random.nextBytes(LARGE_DATA_SIZE))
            val key = table.insert(key = null, data = data)
            assertEquals(data, table.get(key))
        }
    }

    @Test
    fun testPartitionIdRequired() {
        withTable(supportPartitions = true) { table ->
            // partition was not given when it is required
            assertFailsWith(IllegalArgumentException::class) {
                table.insert(key = null, data = "data".encodeToByteString())
            }
        }
    }

    @Test
    fun testNoPartitionSupported() {
        withTable(supportPartitions = false) { table ->
            // partition is given when it is not supported
            assertFailsWith(IllegalArgumentException::class) {
                table.insert(key = null, partitionId = "A", data = "data".encodeToByteString())
            }
        }
    }

    @Test
    fun testNoExpirationSupported() {
        withTable(supportExpiration = false) { table ->
            // expiration is given when it is not supported
            assertFailsWith(IllegalArgumentException::class) {
                val time = TestClock.time + 10.minutes
                table.insert(key = null, expiration = time, data = "data".encodeToByteString())
            }
        }
    }

    @Test
    fun testEnumerateNegativeLimit() {
        withTable() { table ->
            assertFailsWith(IllegalArgumentException::class) {
                table.enumerate(limit = -5)
            }
        }
    }

    @Test
    fun testDeleteAll() {
        withTable(supportExpiration = true, supportPartitions = true) { table ->
            val time = TestClock.time + 10.minutes
            val data = "data".encodeToByteString()
            val key1 = table.insert(key = null, partitionId = "A", data = data)
            val key2 = table.insert(key = null, partitionId = "A", expiration = time, data = data)
            val key3 = table.insert(key = null, partitionId = "A", expiration = time, data = data)
            assertEquals(setOf(key1, key2, key3), table.enumerate(partitionId = "A").toSet())
            assertEquals(data, table.get(key = key1, partitionId = "A"))
            assertEquals(data, table.get(key = key2, partitionId = "A"))
            assertEquals(data, table.get(key = key3, partitionId = "A"))
            table.deleteAll()
            assertEquals(setOf(), table.enumerate(partitionId = "A").toSet())
            assertNull(table.get(key = key1, partitionId = "A"))
            assertNull(table.get(key = key2, partitionId = "A"))
            assertNull(table.get(key = key3, partitionId = "A"))
        }
    }

    @Test
    fun testDeletePartition() {
        withTable(supportExpiration = false, supportPartitions = true) { table ->
            val data = "data".encodeToByteString()
            val key0 = table.insert(key = null, partitionId = "A", data = data)
            val key1 = table.insert(key = null, partitionId = "B", data = data)
            val key2 = table.insert(key = null, partitionId = "B", data = data)
            val key3 = table.insert(key = null, partitionId = "C", data = data)
            assertEquals(setOf(key0), table.enumerate(partitionId = "A").toSet())
            assertEquals(setOf(key1, key2), table.enumerate(partitionId = "B").toSet())
            assertEquals(setOf(key3), table.enumerate(partitionId = "C").toSet())
            table.deletePartition(partitionId = "B")
            assertEquals(setOf(key0), table.enumerate(partitionId = "A").toSet())
            assertEquals(setOf(), table.enumerate(partitionId = "B").toSet())
            assertEquals(setOf(key3), table.enumerate(partitionId = "C").toSet())
            assertNull(table.get(key = key1, partitionId = "B"))
            assertNull(table.get(key = key2, partitionId = "B"))
        }
    }

    private fun withStorage(block: suspend CoroutineScope.(storage: Storage) -> Unit) {
        for (storage in transientStorageList) {
            runBlocking {
                block(storage)
            }
        }
    }

    private fun withTable(
        supportExpiration: Boolean = false,
        supportPartitions: Boolean = false,
        block: suspend CoroutineScope.(table: StorageTable) -> Unit
    ) {
        val tableName = "TestTable${uniqueSuffix()}"
        val tableSpec = StorageTableSpec(tableName, supportPartitions, supportExpiration)
        withStorage { storage ->
            block(storage.getTable(tableSpec))
        }
    }

    private fun uniqueSuffix(): String {
        val timestamp = Clock.System.now().epochSeconds.toString(36)
        val count = uniqueCount++.toString(36)
        return "_${timestamp}_${count}"
    }

    object TestClock: Clock {
        internal var time: Instant = Instant.DISTANT_PAST
        override fun now(): Instant = time
    }

    companion object {
        var uniqueCount: Long = 0
        const val LARGE_DATA_SIZE = 4 * 1024 * 1024  // 4Mb
        const val LONG_NAME_60 = "A12345678901234567890123456789012345678901234567890123456789"
        val transientStorageList by lazy {
            createTransientStorageList(TestClock)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
private fun createTransientStorageList(testClock: Clock): List<Storage> {
    val bundledDb = BundledSQLiteDriver().open(":memory:")
    val nativeDb = NativeSQLiteDriver().open(":memory:")
    return listOf(
        EphemeralStorage(testClock),
        SqliteStorage(
            connection = nativeDb,
            clock = testClock,
            // native sqlite crashes when used with Dispatchers.IO
            coroutineContext = newSingleThreadContext("DB")
        ),
        SqliteStorage(
            connection = bundledDb,
            clock = testClock,
            // bundled sqlite crashes when used with Dispatchers.IO
            coroutineContext = newSingleThreadContext("DB")
        )
    )
}

val knownNames = mutableSetOf<String>()

@OptIn(
    ExperimentalCoroutinesApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalForeignApi::class
)
private fun createPersistentStorage(name: String, testClock: Clock): Storage? {
    val paths = NSSearchPathForDirectoriesInDomains(
        directory = NSCachesDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true
    )
    if (paths.isEmpty()) {
        throw IllegalStateException("No caches directory")
    }
    val dbPath = "${paths[0]}/$name.db"
    if (knownNames.add(name)) {
        if (NSFileManager.defaultManager.fileExistsAtPath(dbPath)) {
            NSFileManager.defaultManager.removeItemAtPath(dbPath, null)
        }
    }
    return SqliteStorage(
        connection = NativeSQLiteDriver().open(dbPath),
        clock = testClock,
        // native sqlite crashes when used with Dispatchers.IO
        coroutineContext = newSingleThreadContext("DB")
    )
}