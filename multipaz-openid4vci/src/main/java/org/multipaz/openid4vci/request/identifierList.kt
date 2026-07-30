package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.revocation.IdentifierList
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val cachedIdentifierListLock = Mutex()

// Maps bucket id to last invalidation time and up-to-date CompressedStatusList (if any)
private var cachedIdentifierListMap = mutableMapOf<String, Pair<Instant, IdentifierList?>>()

suspend fun identifierList(call: ApplicationCall, bucket: String) {
    val cached = cachedIdentifierListLock.withLock {
        cachedIdentifierListMap[bucket]?.second
    }
    val minValidity = Clock.System.now() + 20.seconds
    val identifierList = if (cached != null && cached.expirationTime > minValidity) {
        cached
    } else {
        var list: List<Pair<Int, CredentialState.Status>>
        while (true) {
            val started = Clock.System.now()
            // For now, grab the whole list in one shot
            list = CredentialState.listNonValidCredentials(bucket)
            cachedIdentifierListLock.withLock {
                val entry = cachedIdentifierListMap[bucket]
                // either never invalidated (or requested) or built after last invalidation
                if (entry == null || started > entry.first) {
                    break
                }
            }
        }
        val identifierListBuilder = IdentifierList.Builder()
        for ((index, status) in list) {
            check(status != CredentialState.Status.VALID)
            identifierListBuilder.add(CredentialState.indexToIdentifier(index))
        }
        identifierListBuilder.build().also {
            cachedIdentifierListLock.withLock {
                cachedIdentifierListMap[bucket] = Pair(it.creationTime, it)
            }
        }
    }

    val creation = identifierList.creationTime.toEpochMilliseconds()
    call.response.header(HttpHeaders.LastModified, GMTDate(creation).toHttpDate())
    call.response.header(HttpHeaders.ETag, "W/$creation")
    call.respondBytes(
        bytes = identifierList.serializeAsCwt(
            key = getServerIdentity(ServerIdentity.CREDENTIAL_SIGNING),
            subject = BackendEnvironment.getBaseUrl() + "/identifier_list/$bucket"
        ),
        contentType = IDENTIFIER_LIST_CWT
    )
}

private val IDENTIFIER_LIST_CWT = ContentType("application", "identifierlist+cwt")

suspend fun invalidateIdentifierList(bucket: String) = cachedIdentifierListLock.withLock {
     cachedIdentifierListMap[bucket] = Pair(Clock.System.now(), null)
}
