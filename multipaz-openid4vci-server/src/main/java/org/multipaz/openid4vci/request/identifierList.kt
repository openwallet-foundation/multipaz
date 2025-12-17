package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.date.GMTDate
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.revocation.IdentifierList
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import kotlin.time.Clock
import kotlin.time.Instant

private var cachedIdentifierList: IdentifierList? = null

@Volatile
private var lastInvalidationTime: Instant = Clock.System.now()

suspend fun identifierList(call: ApplicationCall, bucket: String) {
    CredentialFactory.getRegisteredFactories()  // ensure signing key is loaded

    val identifierList = cachedIdentifierList ?: run {
        var list: List<Pair<Int, CredentialState.Status>>
        while (true) {
            val started = Clock.System.now()
            // For now, grab the whole list in one shot
            list = CredentialState.listNonValidCredentials(bucket)
            if (started > lastInvalidationTime) {
                break
            }
        }
        val identifierListBuilder = IdentifierList.Builder()
        for ((index, status) in list) {
            check(status != CredentialState.Status.VALID)
            identifierListBuilder.add(CredentialState.indexToIdentifier(index))
        }
        identifierListBuilder.build().also { cachedIdentifierList = it }
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

fun invalidateIdentifierList() {
    lastInvalidationTime = Clock.System.now()
    cachedIdentifierList = null
}
