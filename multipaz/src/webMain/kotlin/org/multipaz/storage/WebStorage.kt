@file:OptIn(ExperimentalWasmJsInterop::class)

package org.multipaz.storage

import js.array.jsArrayOf
import js.typedarrays.Uint8Array
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.base.BaseStorage
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.util.toBase64Url
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.definedExternally
import kotlin.js.js
import kotlin.js.toJsString
import kotlin.js.unsafeCast

// External declarations for Kotlin/Wasm and Kotlin/JS compatibility.
// All interop types MUST extend JsAny.

internal external interface IDBFactory : JsAny {
    fun open(name: String, version: Double): IDBOpenDBRequest
}

internal external interface IDBRequest : JsAny

internal external interface IDBOpenDBRequest : IDBRequest

internal external interface IDBDatabase : JsAny {
    fun transaction(storeNames: JsAny, mode: String): IDBTransaction
    fun createObjectStore(name: String, options: JsAny? = definedExternally): IDBObjectStore
}

internal external interface IDBTransaction : JsAny {
    fun objectStore(name: String): IDBObjectStore
}

internal external interface IDBObjectStore : JsAny {
    fun get(key: JsAny): IDBRequest
    fun add(value: JsAny): IDBRequest
    fun put(value: JsAny): IDBRequest
    fun delete(key: JsAny): IDBRequest
    fun openCursor(range: JsAny? = definedExternally): IDBRequest
    fun createIndex(name: String, keyPath: JsAny, options: JsAny? = definedExternally): IDBIndex
    fun index(name: String): IDBIndex
}

internal external interface IDBIndex : JsAny {
    fun openCursor(range: JsAny? = definedExternally): IDBRequest
}

internal external interface IDBCursor : JsAny {
    val key: JsAny
    val value: JsAny
    fun delete(): IDBRequest
}

internal external interface IDBKeyRangeFactory : JsAny {
    fun bound(lower: JsAny, upper: JsAny): JsAny
    fun upperBound(upper: JsAny): JsAny
}

internal external interface StorageRecord : JsAny {
    var t: String
    var p: String
    var i: String
    var e: Double
    var d: Uint8Array<*>
}

// Top-level js() expression functions for Kotlin/Wasm compatibility

private fun getIndexedDB(): IDBFactory = js("window.indexedDB")
private fun getIDBKeyRange(): IDBKeyRangeFactory = js("window.IDBKeyRange")

private fun getRequestResult(request: IDBRequest): JsAny? = js("request.result")
private fun getRequestError(request: IDBRequest): JsString? = js("request.error ? request.error.message : null")
private fun getRequestErrorName(request: IDBRequest): JsString? = js("request.error ? request.error.name : null")

private fun setRequestCallback(request: IDBRequest, name: JsString, callback: (JsAny) -> Unit): Unit =
    js("request[name] = callback")

private fun createIdbOptions(keyPath: JsAny): JsAny = js("({ keyPath: keyPath })")
private fun createIndexOptions(unique: Boolean): JsAny = js("({ unique: unique })")

private fun createRecord(t: JsString, p: JsString, i: JsString, d: Uint8Array<*>, e: Double): StorageRecord =
    js("({ t: t, p: p, i: i, d: d, e: e })")

private fun callCursorContinue(cursor: IDBCursor): Unit = js("cursor.continue()")

private fun getCursorValue(cursor: IDBCursor): StorageRecord = js("cursor.value")

private fun toJsNumberFromDouble(value: Double): JsAny = js("value")

/**
 * [Storage] implementation based on IndexedDB for both Kotlin/JS and Kotlin/Wasm.
 */
class WebStorage(
    val dbName: String = "MultipazStorage",
    clock: Clock = Clock.System
) : BaseStorage(clock) {

    private val dbDeferred = CompletableDeferred<IDBDatabase>()

    init {
        val request = getIndexedDB().open(dbName, 1.0)
        val dbDef = dbDeferred

        setRequestCallback(request, "onupgradeneeded".toJsString()) {
            val res = getRequestResult(request)
            if (res != null) {
                val db = res.unsafeCast<IDBDatabase>()
                val keyPath = jsArrayOf("t".toJsString(), "p".toJsString(), "i".toJsString())
                val store = db.createObjectStore("records", createIdbOptions(keyPath))
                store.createIndex("by_expiration", "e".toJsString(), createIndexOptions(false))
            }
        }

        setRequestCallback(request, "onsuccess".toJsString()) {
            val res = getRequestResult(request)
            if (res != null) {
                dbDef.complete(res.unsafeCast<IDBDatabase>())
            }
        }

        setRequestCallback(request, "onerror".toJsString()) {
            dbDef.completeExceptionally(Throwable("Error opening IndexedDB: ${getRequestError(request)}"))
        }
    }

    internal suspend fun getDb(): IDBDatabase = dbDeferred.await()

    override suspend fun createTable(tableSpec: StorageTableSpec): BaseStorageTable {
        return WebStorageTable(this, tableSpec)
    }

    internal suspend fun <T : JsAny> awaitRequest(request: IDBRequest): T? = suspendCancellableCoroutine { cont ->
        setRequestCallback(request, "onsuccess".toJsString()) {
            val res = getRequestResult(request)
            cont.resume(res?.unsafeCast<T>())
        }
        setRequestCallback(request, "onerror".toJsString()) {
            val errorName = getRequestErrorName(request)?.toString()
            if (errorName == "ConstraintError") {
                cont.resumeWithException(KeyExistsStorageException("Record already exists"))
            } else {
                cont.resumeWithException(Throwable("IndexedDB request error: ${getRequestError(request)}"))
            }
        }
    }
}

private class WebStorageTable(
    override val storage: WebStorage,
    spec: StorageTableSpec
) : BaseStorageTable(spec) {

    override suspend fun get(key: String, partitionId: String?): ByteString? {
        checkPartition(partitionId)
        val p = partitionId ?: ""
        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readonly")
        val store = tx.objectStore("records")
        val idbKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), key.toJsString()).unsafeCast<JsAny>()
        val result = storage.awaitRequest<StorageRecord>(store.get(idbKey)) ?: return null

        if (result.e != 0.0 && result.e < storage.clock.now().epochSeconds) {
            return null
        }
        return ByteString(result.d.toByteArray())
    }

    override suspend fun insert(
        key: String?,
        data: ByteString,
        partitionId: String?,
        expiration: Instant
    ): String {
        if (key != null) checkKey(key)
        checkPartition(partitionId)
        checkExpiration(expiration)

        val p = partitionId ?: ""
        val db = storage.getDb()
        val now = storage.clock.now().epochSeconds

        var done = false
        var newKey = ""
        do {
            newKey = key ?: Random.nextBytes(9).toBase64Url()
            try {
                val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
                val store = tx.objectStore("records")

                if (key != null && spec.supportExpiration) {
                    val idbKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), key.toJsString()).unsafeCast<JsAny>()
                    val existing = storage.awaitRequest<StorageRecord>(store.get(idbKey))
                    if (existing != null) {
                        if (existing.e != 0.0 && existing.e < now) {
                            storage.awaitRequest<JsAny>(store.delete(idbKey))
                        } else {
                            throw KeyExistsStorageException("Record already exists")
                        }
                    }
                }

                val record = createRecord(
                    spec.name.toJsString(),
                    p.toJsString(),
                    newKey.toJsString(),
                    data.toByteArray().toUint8Array(),
                    if (spec.supportExpiration && expiration != Instant.DISTANT_FUTURE) expiration.epochSeconds.toDouble() else 0.0
                )
                storage.awaitRequest<JsAny>(store.add(record))
                done = true
            } catch (e: Throwable) {
                if (key != null) throw e
            }
        } while (!done)
        return newKey
    }

    override suspend fun update(
        key: String,
        data: ByteString,
        partitionId: String?,
        expiration: Instant?
    ) {
        checkPartition(partitionId)
        if (expiration != null) checkExpiration(expiration)
        val p = partitionId ?: ""
        val db = storage.getDb()
        val now = storage.clock.now().epochSeconds

        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
        val store = tx.objectStore("records")
        val idbKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), key.toJsString()).unsafeCast<JsAny>()
        val existing = storage.awaitRequest<StorageRecord>(store.get(idbKey))
            ?: throw NoRecordStorageException("No record found")

        if (existing.e != 0.0 && existing.e < now) {
            throw NoRecordStorageException("Record expired")
        }

        val record = createRecord(
            spec.name.toJsString(),
            p.toJsString(),
            key.toJsString(),
            data.toByteArray().toUint8Array(),
            if (expiration != null) {
                if (expiration != Instant.DISTANT_FUTURE) expiration.epochSeconds.toDouble() else 0.0
            } else {
                existing.e
            }
        )
        storage.awaitRequest<JsAny>(store.put(record))
    }

    override suspend fun delete(key: String, partitionId: String?): Boolean {
        checkPartition(partitionId)
        val p = partitionId ?: ""
        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
        val store = tx.objectStore("records")
        val idbKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), key.toJsString()).unsafeCast<JsAny>()
        val existing = storage.awaitRequest<StorageRecord>(store.get(idbKey))
        if (existing == null || (existing.e != 0.0 && existing.e < storage.clock.now().epochSeconds)) {
            return false
        }
        storage.awaitRequest<JsAny>(store.delete(idbKey))
        return true
    }

    override suspend fun deleteAll() {
        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
        val store = tx.objectStore("records")
        val range = getIDBKeyRange().bound(
            jsArrayOf(spec.name.toJsString()).unsafeCast<JsAny>(),
            jsArrayOf(spec.name.toJsString(), "\uffff".toJsString()).unsafeCast<JsAny>()
        )
        
        suspendCancellableCoroutine<Unit> { cont ->
            val request = store.openCursor(range)
            
            setRequestCallback(request, "onsuccess".toJsString()) {
                val res = getRequestResult(request)
                if (res != null) {
                    val cursor = res.unsafeCast<IDBCursor>()
                    cursor.delete()
                    callCursorContinue(cursor)
                } else {
                    cont.resume(Unit)
                }
            }
            setRequestCallback(request, "onerror".toJsString()) { 
                cont.resumeWithException(Throwable("deleteAll failed: ${getRequestError(request)}"))
            }
        }
    }

    override suspend fun deletePartition(partitionId: String) {
        checkPartition(partitionId)
        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
        val store = tx.objectStore("records")
        val range = getIDBKeyRange().bound(
            jsArrayOf(spec.name.toJsString(), partitionId.toJsString()).unsafeCast<JsAny>(),
            jsArrayOf(spec.name.toJsString(), partitionId.toJsString(), "\uffff".toJsString()).unsafeCast<JsAny>()
        )
        
        suspendCancellableCoroutine<Unit> { cont ->
            val request = store.openCursor(range)
            
            setRequestCallback(request, "onsuccess".toJsString()) {
                val res = getRequestResult(request)
                if (res != null) {
                    val cursor = res.unsafeCast<IDBCursor>()
                    cursor.delete()
                    callCursorContinue(cursor)
                } else {
                    cont.resume(Unit)
                }
            }
            setRequestCallback(request, "onerror".toJsString()) { 
                cont.resumeWithException(Throwable("deletePartition failed: ${getRequestError(request)}"))
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
        if (limit == 0) return listOf()

        val p = partitionId ?: ""
        val startKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), (afterKey?.plus("\u0000") ?: "").toJsString()).unsafeCast<JsAny>()
        val endKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), "\uffff".toJsString()).unsafeCast<JsAny>()
        val range = getIDBKeyRange().bound(startKey, endKey)

        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readonly")
        val store = tx.objectStore("records")
        
        return suspendCancellableCoroutine { cont ->
            val list = mutableListOf<String>()
            val request = store.openCursor(range)
            val now = storage.clock.now().epochSeconds
            
            setRequestCallback(request, "onsuccess".toJsString()) {
                val res = getRequestResult(request)
                if (res != null && list.size < limit) {
                    val cursor = res.unsafeCast<IDBCursor>()
                    val record = getCursorValue(cursor)
                    if (record.e == 0.0 || record.e >= now) {
                        list.add(record.i)
                    }
                    callCursorContinue(cursor)
                } else {
                    cont.resume(list)
                }
            }
            setRequestCallback(request, "onerror".toJsString()) { 
                cont.resumeWithException(Throwable("enumerate failed: ${getRequestError(request)}"))
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
        if (limit == 0) return listOf()

        val p = partitionId ?: ""
        val startKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), (afterKey?.plus("\u0000") ?: "").toJsString()).unsafeCast<JsAny>()
        val endKey = jsArrayOf(spec.name.toJsString(), p.toJsString(), "\uffff".toJsString()).unsafeCast<JsAny>()
        val range = getIDBKeyRange().bound(startKey, endKey)

        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readonly")
        val store = tx.objectStore("records")
        
        return suspendCancellableCoroutine { cont ->
            val list = mutableListOf<Pair<String, ByteString>>()
            val request = store.openCursor(range)
            val now = storage.clock.now().epochSeconds
            
            setRequestCallback(request, "onsuccess".toJsString()) {
                val res = getRequestResult(request)
                if (res != null && list.size < limit) {
                    val cursor = res.unsafeCast<IDBCursor>()
                    val record = getCursorValue(cursor)
                    if (record.e == 0.0 || record.e >= now) {
                        list.add(Pair(record.i, ByteString(record.d.toByteArray())))
                    }
                    callCursorContinue(cursor)
                } else {
                    cont.resume(list)
                }
            }
            setRequestCallback(request, "onerror".toJsString()) { 
                cont.resumeWithException(Throwable("enumerateWithData failed: ${getRequestError(request)}"))
            }
        }
    }

    override suspend fun purgeExpired() {
        val db = storage.getDb()
        val tx = db.transaction(jsArrayOf("records".toJsString()).unsafeCast<JsAny>(), "readwrite")
        val store = tx.objectStore("records")
        val now = storage.clock.now().epochSeconds
        val range = getIDBKeyRange().upperBound(toJsNumberFromDouble(now.toDouble()))

        suspendCancellableCoroutine<Unit> { cont ->
            val request = store.index("by_expiration").openCursor(range)
            
            setRequestCallback(request, "onsuccess".toJsString()) {
                val res = getRequestResult(request)
                if (res != null) {
                    val cursor = res.unsafeCast<IDBCursor>()
                    val record = getCursorValue(cursor)
                    if (record.t == spec.name) {
                        // Fire and forget delete request
                        cursor.delete()
                    }
                    callCursorContinue(cursor)
                } else {
                    cont.resume(Unit)
                }
            }
            setRequestCallback(request, "onerror".toJsString()) { 
                cont.resumeWithException(Throwable("purgeExpired failed: ${getRequestError(request)}"))
            }
        }
    }
}
