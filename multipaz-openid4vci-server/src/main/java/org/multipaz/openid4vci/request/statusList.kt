package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.date.GMTDate
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.credential.CredentialFactoryBase
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.getBaseUrl
import org.multipaz.statuslist.CompressedStatusList
import org.multipaz.statuslist.StatusList
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Instant

@Volatile
private var cachedStatusList: CompressedStatusList? = null

@Volatile
private var lastInvalidationTime: Instant = Clock.System.now()

suspend fun statusList(call: ApplicationCall) {

    CredentialFactory.getRegisteredFactories()  // ensure signing key is loaded

    val statusList = cachedStatusList ?: run {
        var list: List<Pair<Int, CredentialState.Status>>
        while (true) {
            val started = Clock.System.now()
            // For now, grab the whole list in one shot
            list = CredentialState.listNonValidCredentials()
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

    val jwt = statusList.serializeAsJwt(
        key = CredentialFactoryBase.serverKey,
        issuer = BackendEnvironment.getBaseUrl() + "/status_list"
    )
    val creation = GMTDate(statusList.creationTime.toEpochMilliseconds())
    Logger.i("KMY", "Creation: ${creation.toHttpDate()}")
    call.response.header(HttpHeaders.LastModified, creation.toHttpDate())
    call.respondText(
        text = jwt,
        contentType = APPLICATION_JWT
    )
}

private val APPLICATION_JWT = ContentType("application", "jwt")

fun invalidateStatusList() {
    lastInvalidationTime = Clock.System.now()
    cachedStatusList = null
}
