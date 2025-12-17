package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.date.GMTDate
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.revocation.CompressedStatusList
import org.multipaz.revocation.StatusList
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import kotlin.time.Clock
import kotlin.time.Instant

@Volatile
private var cachedStatusList: CompressedStatusList? = null

@Volatile
private var lastInvalidationTime: Instant = Clock.System.now()

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

    CredentialFactory.getRegisteredFactories()  // ensure signing key is loaded

    val statusList = cachedStatusList ?: run {
        var list: List<Pair<Int, CredentialState.Status>>
        while (true) {
            val started = Clock.System.now()
            // For now, grab the whole list in one shot
            list = CredentialState.listNonValidCredentials(bucket)
            if (started > lastInvalidationTime) {
                break
            }
        }
        val moreThanOneBit = list.find { (_, status) -> status.encoded > 1 } != null
        val statusListBuilder = StatusList.Builder(if (moreThanOneBit) 2 else 1)
        for ((index, status) in list) {
            statusListBuilder.addStatus(index, status.encoded)
        }
        statusListBuilder.build().compress().also { cachedStatusList = it }
    }

    val creation = statusList.creationTime.toEpochMilliseconds()
    call.response.header(HttpHeaders.LastModified, GMTDate(creation).toHttpDate())
    call.response.header(HttpHeaders.ETag, "W/$creation")
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

private val STATUSLIST_JWT = ContentType("application", "statuslist+jwt")
private val STATUSLIST_CWT = ContentType("application", "statuslist+cwt")

private val COMMA_SEPARATOR = Regex(",\\s*")

fun invalidateStatusList() {
    lastInvalidationTime = Clock.System.now()
    cachedStatusList = null
}
