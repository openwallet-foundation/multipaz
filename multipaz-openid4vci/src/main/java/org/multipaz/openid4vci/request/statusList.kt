package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.revocation.CompressedStatusList
import org.multipaz.revocation.StatusList
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val cachedStatusListLock = Mutex()

// Maps bucket id to last invalidation time and up-to-date CompressedStatusList (if any)
private var cachedStatusListMap = mutableMapOf<String, Pair<Instant, CompressedStatusList?>>()

suspend fun statusList(call: ApplicationCall, bucket: String) {
    val accept = call.request.headers[HttpHeaders.Accept] ?: ""
    var useCwt = false  // arbitrary, bias towards text-based format
    for (acceptedPattern in accept.split(COMMA_SEPARATOR)) {
        if (STATUSLIST_JWT.match(acceptedPattern)) {
            useCwt = false
            break
        }
        if (STATUSLIST_CWT.match(acceptedPattern)) {
            useCwt = true
            break
        }
    }

    val cached = cachedStatusListLock.withLock {
        cachedStatusListMap[bucket]?.second
    }
    val minValidity = Clock.System.now() + 20.seconds
    val statusList = if (cached != null && cached.expirationTime > minValidity) {
        cached
    } else {
        // Need to be initialized only to make the compiler happy
        var list: List<Pair<Int, CredentialState.Status>> = emptyList()
        while (true) {
            val started = Clock.System.now()
            // For now, grab the whole list in one shot
            list = CredentialState.listNonValidCredentials(bucket)
            cachedStatusListLock.withLock {
                val entry = cachedStatusListMap[bucket]
                // either never invalidated (or requested) or built after last invalidation
                if (entry == null || started > entry.first) {
                    break
                }
            }
        }
        val moreThanOneBit = list.find { (_, status) -> status.encoded > 1 } != null
        val statusListBuilder = StatusList.Builder(if (moreThanOneBit) 2 else 1)
        for ((index, status) in list) {
            statusListBuilder.addStatus(index, status.encoded)
        }
        statusListBuilder.build().compress().also {
            cachedStatusListLock.withLock {
                cachedStatusListMap[bucket] = Pair(it.creationTime, it)
            }
        }
    }

    if (!handleRevocationDataNotModified(call, statusList)) {
        val serverKey = getServerIdentity(ServerIdentity.CREDENTIAL_SIGNING)
        if (useCwt) {
            call.respondBytes(
                bytes = statusList.serializeAsCwt(
                    key = serverKey,
                    subject = BackendEnvironment.getBaseUrl() + "/status_list/$bucket"
                ),
                contentType = STATUSLIST_CWT
            )
        } else {
            call.respondText(
                text = statusList.serializeAsJwt(
                    key = serverKey,
                    subject = BackendEnvironment.getBaseUrl() + "/status_list/$bucket"
                ),
                contentType = STATUSLIST_JWT
            )
        }
    }
}

private val STATUSLIST_JWT = ContentType("application", "statuslist+jwt")
private val STATUSLIST_CWT = ContentType("application", "statuslist+cwt")

private val COMMA_SEPARATOR = Regex(",\\s*")

suspend fun invalidateStatusList(bucket: String) = cachedStatusListLock.withLock {
    cachedStatusListMap[bucket] = Pair(Clock.System.now(), null)
}
