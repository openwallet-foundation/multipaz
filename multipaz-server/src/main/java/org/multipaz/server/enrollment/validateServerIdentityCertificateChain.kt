package org.multipaz.server.enrollment

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.OID
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.cache
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.Logger
import org.multipaz.webtoken.basicCertificateChainValidator
import kotlin.time.Instant

/**
 * Validates certificate chain created using an identity returned by [getServerIdentity]
 * including, if possible, root certificate.
 *
 * Only validates root certificates issued by HTTPS-hosted servers that match the list provided by
 * "ca_trust_servers" setting (which must be a JSON list of strings), unless running on localhost
 * url.
 *
 * @return `true` if trusted root certificate is found and the full chain is valid, `false` if
 *    the chain is valid, but the root certificate could not be found.
 * @throws InvalidRequestException if the certificate chain is not valid
 * @throws IllegalStateException is the root certificate is not self-issued
 */
suspend fun validateServerIdentityCertificateChain(
    serverIdentity: ServerIdentity,
    certChain: X509CertChain,
    instant: Instant
): Boolean {
    val issuerCN = certChain.certificates.last().issuer.components[OID.COMMON_NAME.oid]?.value
    val prefix = "${serverIdentity.commonName} Root at "
    if (issuerCN != null && issuerCN.startsWith(prefix)) {
        val serverUrl = issuerCN.substring(prefix.length)
        val rootCert = try {
            getIdentityRootCertificate(serverIdentity, serverUrl)
        } catch (err: IllegalStateException) {
            Logger.e(TAG, "Failed to fetch multipaz-issued root certificate", err)
            null
        }
        if (rootCert != null) {
            val fullChain = X509CertChain(buildList {
                addAll(certChain.certificates)
                add(rootCert)
            })
            if (rootCert.issuer != rootCert.subject) {
                throw IllegalStateException("Root certificate issuer/subject mismatch")
            }
            rootCert.verify(rootCert.ecPublicKey)
            basicCertificateChainValidator(fullChain, instant)
            return true
        }
    }
    return basicCertificateChainValidator(certChain, instant)
}

private suspend fun getIdentityRootCertificate(
    serverIdentity: ServerIdentity,
    serverUrl: String
): X509Cert {
    val baseUrl = BackendEnvironment.getBaseUrl()
    if (baseUrl == serverUrl) {
        return getLocalRootCertificate(serverIdentity, createOnRequest = false)
    }
    // When running on localhost, trust all CAs
    if (!baseUrl.startsWith("http://localhost:")) {
        checkServerTrust(serverUrl, "ca_trust_servers")
    }
    val certUrl = "$serverUrl/ca/${serverIdentity.jsonName}"
    return BackendEnvironment.cache(X509Cert::class, certUrl) { _, _ ->
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        Logger.i(TAG, "Attempting to fetch CA certificate from '$certUrl'...")
        val response = httpClient.get(certUrl) {
            // DER is more compact, so prefer that
            headers.append(name = HttpHeaders.Accept, value = "application/pkix-cert")
        }
        if (response.status != HttpStatusCode.OK) {
            throw InvalidRequestException("Could not reach: $certUrl")
        }
        X509Cert(ByteString(response.readRawBytes()))
    }
}

private const val TAG = "validateServerIdentityCertificateChain"
