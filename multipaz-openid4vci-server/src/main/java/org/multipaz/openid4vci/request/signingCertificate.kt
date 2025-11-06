package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * GET request that displays signing certificate for a given credential id in pem format.
 */
suspend fun signingCertificate(call: ApplicationCall) {
    val credentialId = call.parameters["credential_id"]
        ?: throw InvalidRequestException("'credential_id' parameter required")
    val credentialFactory = CredentialFactory.getRegisteredFactories().byOfferId[credentialId]
        ?: throw InvalidRequestException("invalid 'credential_id' parameter value")
    val certificate = credentialFactory.signingKey.certChain.certificates.last()
    call.respondText(ContentType.Text.Plain) {
        certificate.toPem()
    }
}
