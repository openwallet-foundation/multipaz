package org.multipaz.server.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getCrl
import org.multipaz.server.enrollment.getLocalRootCertificate

/**
 * Serves root certificates created by this server and their revocation lists.
 */
fun Routing.certificateAuthority(createOnRequest: Boolean = false) {
    get("/crl/{identity}") {
        val identity = call.parameters["identity"]!!
        crl(call, ServerIdentity.fromJsonName(identity), createOnRequest)
    }
    get("/ca/{identity}") {
        val identity = call.parameters["identity"]!!
        caCert(call, ServerIdentity.fromJsonName(identity), createOnRequest)
    }
}

private suspend fun crl(
    call: ApplicationCall,
    serverIdentity: ServerIdentity,
    createOnRequest: Boolean
) {
    try {
        val crl = getCrl(serverIdentity, createOnRequest)
        call.respondBytes(contentType = CRL_CONTENT_TYPE) { crl.encoded.toByteArray() }
    } catch (_: IllegalStateException) {
        call.respondText("$serverIdentity not found", status = HttpStatusCode.NotFound)
    }
}

private suspend fun caCert(
    call: ApplicationCall,
    serverIdentity: ServerIdentity,
    createOnRequest: Boolean
) {
    val accept = call.request.headers[HttpHeaders.Accept] ?: ""
    var usePem = true  // arbitrary, bias towards text-based format
    for (acceptedPattern in accept.split(COMMA_SEPARATOR)) {
        if (ContentType.Text.Plain.match(acceptedPattern)) {
            usePem = true
            break
        }
        if (DER_CERT_CONTENT_TYPE.match(acceptedPattern)) {
            usePem = false
            break
        }
    }
    try {
        val cert = getLocalRootCertificate(serverIdentity, createOnRequest)
        if (usePem) {
            call.respondText(
                contentType = ContentType.Text.Plain,
                text = cert.toPem()
            )
        } else {
            call.respondBytes(
                contentType = DER_CERT_CONTENT_TYPE
            ) {
                cert.encoded.toByteArray()
            }
        }
    } catch (_: IllegalStateException) {
        call.respondText("$serverIdentity not found", status = HttpStatusCode.NotFound)
    }
}

private val CRL_CONTENT_TYPE = ContentType("application", "pkix-crl")

private val DER_CERT_CONTENT_TYPE = ContentType("application", "pkix-cert")

private val COMMA_SEPARATOR = Regex(",\\s*")
