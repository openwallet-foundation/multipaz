package org.multipaz.revocation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.etag
import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509Cert
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Storage-backed implementation of [RevocationChecker] that caches downloaded data (status and
 * identifier lists) in a [StorageTable].
 *
 * Revocation data is validated at download time, when loaded from cache it is not re-validated.
 * When revocation data is found in cache, it is checked for freshness in the following manner:
 * - if revocation data server has used `ETag` or `Last-Modified` headers, a conditional HTTP GET
 *   request is sent to the server; HTTP status `304 Not Modified` means that cached data is
 *   fresh to be used, otherwise fresh data is sent from the server and replaces cached one.
 * - if revocation data server has not sent these headers, cached data is assumed fresh for its
 *   specified TTL time, or, absent that, expiration time.
 * - if HTTP update fails, cached data is used, even if it is expired; stale data is assumed
 *   to be better than no data; cache entries are purged after the time specified by
 *   [cachingDuration], which should be longer than a typical credential validity period.
 *
 * @param storage Storage instance used to persist revocation list caches.
 * @param httpClient Ktor [HttpClient] used to fetch revocation status and identifier lists over HTTP/HTTPS.
 * @param httpTimeout Timeout duration for network requests fetching revocation lists.
 * @param cachingDuration how long to keep revocation data in cache
 * @param useETag allow `ETag` header use, mostly exposed for testing only
 * @param useETag allow `Last-Modified` header use, mostly exposed for testing only
 */
class CachingRevocationChecker(
    private val storage: Storage,
    private val httpClient: HttpClient = HttpClient(),
    private val httpTimeout: Duration = 10.seconds,
    private val cachingDuration: Duration = 60.days,
    private val useETag: Boolean = true,
    private val useLastModified: Boolean = true
) : RevocationChecker {

    private suspend fun getCacheTable(): StorageTable {
        return storage.getTable(cacheTableSpec)
    }

    override suspend fun clearCache() {
        try {
            getCacheTable().deleteAll()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Failed to clear revocation cache", e)
        }
    }

    override suspend fun check(
        revocationStatus: RevocationStatus,
        issuerCert: X509Cert?,
        onlyTrusted: Boolean,
        atTime: Instant,
        bypassCache: Boolean,
        preferJwt: Boolean
    ): RevocationCheckResult = when (revocationStatus) {
            is RevocationStatus.StatusList ->
                checkStatusList(revocationStatus, issuerCert, onlyTrusted, atTime, bypassCache, preferJwt)
            is RevocationStatus.IdentifierList ->
                checkIdentifierList(revocationStatus, issuerCert, onlyTrusted, atTime, bypassCache)
            is RevocationStatus.Unknown -> RevocationCheckResult(
                state = RevocationCheckState.UNKNOWN,
                isTrusted = false,
                error = IllegalStateException("Unknown revocation status format")
            )
        }

    private suspend fun fetchOrGetCached(
        uri: String,
        acceptHeader: String? = null,
        bypassCache: Boolean = false,
        parser: suspend (bytes: ByteArray, contentType: ContentType?) -> Pair<RevocationData, Boolean>
    ): Pair<StoredRevocationData?, Exception?> {
        val table = getCacheTable()
        val rawCachedData = if (bypassCache) {
            null
        } else {
            try {
                table.get(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Error checking revocation cache for $uri", e)
                null
            }
        }

        val cachedData = rawCachedData?.let {
            try {
                StoredRevocationData.fromCbor(it.toByteArray())
            } catch (e: Exception) {
                Logger.e(TAG, "Error parsing revocation cache for $uri", e)
                null
            }
        }

        if (cachedData != null
            && (!useLastModified || cachedData.lastModified == null)
            && (!useETag || cachedData.etag == null)) {
            // We have cached data, but the server does not support conditional fetches or they
            // are disabled in settings. Unconditional fetch on every check can be too expensive,
            // so just work off the expiration/ttl time in the revocation data.
            if (cachedData.data.expirationTime > Clock.System.now()) {
                return Pair(cachedData, null)
            }
        }

        val response = try {
            httpClient.get(uri) {
                timeout {
                    requestTimeoutMillis = httpTimeout.inWholeMilliseconds
                }
                if (cachedData != null) {
                    if (useETag && cachedData.etag != null) {
                        headers.append(HttpHeaders.IfNoneMatch, cachedData.etag)
                    } else if (useLastModified && cachedData.lastModified != null) {
                        headers.append(HttpHeaders.IfModifiedSince, cachedData.lastModified)
                    }
                }
                if (acceptHeader != null) {
                    headers.append(HttpHeaders.Accept, acceptHeader)
                }
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            return Pair(cachedData, err)
        }

        if (response.status == HttpStatusCode.NotModified) {
            return if (cachedData != null) {
                Pair(cachedData, null)
            } else {
                Pair(null, IllegalStateException("Unexpected HTTP Status ${response.status}"))
            }
        }

        if (response.status != HttpStatusCode.OK) {
            return Pair(cachedData, IllegalStateException("HTTP Status ${response.status}"))
        }

        try {
            val (data, isTrusted) = parser.invoke(response.readRawBytes(), response.contentType())
            val updatedCacheEntry = StoredRevocationData(
                lastModified = response.headers[HttpHeaders.LastModified],
                etag = response.etag(),
                isTrusted = isTrusted,
                data = data
            )
            val serialized = ByteString(updatedCacheEntry.toCbor())
            val expiration = Clock.System.now() + cachingDuration
            if (rawCachedData == null) {
                try {
                    table.insert(uri, serialized, expiration = expiration)
                } catch (err: KeyExistsStorageException) {
                    // another thread might have written it meanwhile
                    Logger.w(TAG, "Cache entry already exists: possible access from multiple coroutines", err)
                }
            } else {
                try {
                    table.update(uri, serialized, expiration = expiration)
                } catch (err: NoRecordStorageException) {
                    // cache might have been cleared
                    Logger.w(TAG, "Cache entry was cleared from a different coroutine", err)
                }
            }
            return Pair(updatedCacheEntry, null)
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            return Pair(cachedData, err)
        }
    }

    private suspend fun checkStatusList(
        status: RevocationStatus.StatusList,
        issuerCert: X509Cert?,
        onlyTrusted: Boolean,
        atTime: Instant,
        bypassCache: Boolean,
        preferJwt: Boolean
    ): RevocationCheckResult {
        val cert = status.certificate ?: issuerCert
        return fetchOrGetCached(
            uri = status.uri,
            acceptHeader = if (preferJwt) {
                "$STATUSLIST_JWT, $STATUSLIST_CWT;q=0.9"
            } else {
                "$STATUSLIST_CWT, $STATUSLIST_JWT;q=0.9"
            },
            bypassCache = bypassCache
        ) { bytes, contentType ->
            if (contentType != STATUSLIST_JWT) {
                try {
                    Logger.dCbor(TAG, "Status list (CWT) for ${status.uri}", bytes)
                    return@fetchOrGetCached Pair(
                        first = CompressedStatusList.fromCwt(
                            cwt = bytes,
                            publicKey = cert?.ecPublicKey,
                            atTime = atTime
                        ),
                        second = true
                    )
                } catch (err: InvalidRequestException) {
                    if (onlyTrusted) {
                        throw err
                    }
                    // Untrusted path
                    Logger.w(TAG, "Status list (CWT) could not be validated, attempt using it anyway", err)
                    return@fetchOrGetCached Pair(
                        first = CompressedStatusList.fromCwtNoTrust(bytes),
                        second = false
                    )
                } catch (err: IllegalArgumentException) {
                    if (contentType == STATUSLIST_CWT) {
                        throw err
                    }
                }
            }
            val jwtString = bytes.decodeToString()
            Logger.d(TAG, "Status list (JWT) for ${status.uri}: $jwtString")
            try {
                Pair(
                    first = CompressedStatusList.fromJwt(
                        jwt = jwtString,
                        publicKey = cert?.ecPublicKey,
                        atTime = atTime
                    ),
                    second = true
                )
            } catch (err: InvalidRequestException) {
                if (onlyTrusted) {
                    throw err
                }
                // Untrusted path
                Logger.w(TAG, "Status list (JWT) could not be validated, attempt using it anyway", err)
                Pair(first = CompressedStatusList.fromJwtNoTrust(jwtString), second = false)
            }
        }.let { (item, error) ->
            if (item == null) {
                RevocationCheckResult(RevocationCheckState.UNKNOWN, false, error)
            } else if (onlyTrusted && !item.isTrusted) {
                RevocationCheckResult(
                    state = RevocationCheckState.UNKNOWN,
                    isTrusted = false,
                    error = InvalidRequestException("Status list signature could not be verified")
                )
            } else {
                try {
                    val statusList = (item.data as CompressedStatusList).decompress()
                    when (val code = statusList[status.idx]) {
                        0 -> RevocationCheckResult(RevocationCheckState.VALID, item.isTrusted, error)
                        1 -> RevocationCheckResult(RevocationCheckState.INVALID, item.isTrusted, error)
                        2 -> RevocationCheckResult(RevocationCheckState.SUSPENDED, item.isTrusted, error)
                        else -> RevocationCheckResult(
                            state = RevocationCheckState.UNKNOWN,
                            isTrusted = false,
                            error = IllegalStateException("Unknown revocation code $code")
                        )
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    RevocationCheckResult(RevocationCheckState.UNKNOWN, false, err)
                }
            }
        }
    }

    private suspend fun checkIdentifierList(
        status: RevocationStatus.IdentifierList,
        issuerCert: X509Cert?,
        onlyTrusted: Boolean,
        atTime: Instant,
        bypassCache: Boolean
    ): RevocationCheckResult {
        if (onlyTrusted && issuerCert == null) {
            // This call can only succeed for credentials that carry certificate in their identifier
            // list, which is not common and should not be relied upon.
            Logger.e(TAG, "suspicious checkIdentifierList")
        }
        val cert = status.certificate ?: issuerCert
        return fetchOrGetCached(
            uri = status.uri,
            bypassCache = bypassCache
        ) { bytes, _ ->
            Logger.dCbor(TAG, "Identifier list (CWT) for ${status.uri}", bytes)
            try {
                Pair(
                    first = IdentifierList.fromCwt(
                        cwt = bytes,
                        publicKey = cert?.ecPublicKey,
                        atTime = atTime
                    ),
                    second = true
                )
            } catch (err: InvalidRequestException) {
                if (onlyTrusted) {
                    throw err
                }
                Logger.w(TAG, "Identifier list could not be validated, attempt using it anyway", err)
                // Trust cannot be verified
                Pair(first = IdentifierList.fromCwtNoTrust(bytes), second = false)
            }
        }.let { (item, error) ->
            if (item == null) {
                RevocationCheckResult(RevocationCheckState.UNKNOWN, false, error)
            } else if (onlyTrusted && !item.isTrusted) {
                RevocationCheckResult(
                    state = RevocationCheckState.UNKNOWN,
                    isTrusted = false,
                    error = InvalidRequestException("Identifier list signature could not be verified")
                )
            } else {
                val identifierList = item.data as IdentifierList
                if (identifierList.contains(status.id)) {
                    RevocationCheckResult(RevocationCheckState.INVALID, item.isTrusted, error)
                } else {
                    RevocationCheckResult(RevocationCheckState.VALID, item.isTrusted, error)
                }
            }
        }
    }

    @CborSerializable
    internal data class StoredRevocationData(
        val lastModified: String?,
        val etag: String?,
        val isTrusted: Boolean,
        val data: RevocationData,
    ) {
        companion object
    }

    companion object {
        private const val TAG = "CachingRevocationChecker"
        private val STATUSLIST_JWT = ContentType("application", "statuslist+jwt")
        private val STATUSLIST_CWT = ContentType("application", "statuslist+cwt")

        private val cacheTableSpec = StorageTableSpec(
            name = "RevocationCache",
            supportExpiration = true,
            supportPartitions = false
        )
    }
}
