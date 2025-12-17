package org.multipaz.server.enrollment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod
import org.multipaz.securearea.KeyAttestation
import kotlin.time.Instant

/**
 * Server-to-server RPC interface exposed by Multipaz servers that accept remote "enrollment"
 * from another server.
 *
 * Remote enrollment allows multipaz `records` server to act as Certificate Authority for
 * all other servers that run on valid HTTPS hosts. By default all multipaz servers only
 * trust the server running at "https://issuer.multipaz.org/records" to access this interface
 * (this can be changed using "enrollment_server_url" setting).
 */
@RpcInterface
interface Enrollment {
    /** Notifies this server that remote server key changed and should be re-fetched. */
    @RpcMethod
    suspend fun resetEnrollmentKey()

    /**
     * Initial step of a certificate issuance.
     *
     * Remote server (that acts as a Certificate Authority) requests data for a new certificate.
     *
     * @param requestId if this server asked to be enrolled, this will indicate the
     *    enrollment request id; it is an error if the the request id is not `null` and not
     *    known to this server; this serves as protection against malicious enrollment requests.
     * @param identity defines the purpose of the certificate that will be issued
     * @param nonce nonce/challenge that will be associated with the private key in the
     *    `SecureArea` that holds it.
     * @param expiration when the certificate will expire (so that private key expiration can be
     *    set to the same date/time)
     * @return information to create a certificate
     */
    @RpcMethod
    suspend fun request(
        requestId: String?,
        identity: ServerIdentity,
        nonce: ByteString,
        expiration: Instant
    ): EnrollmentRequest

    /**
     * Provides certificate signed by CA for the given server identity.
     *
     * @param requestId request id as was previously passed to [request] method
     * @param identity defines the purpose of the issued certificate
     * @param alias key alias as returned in [EnrollmentRequest.alias]
     * @param certChain certificate chain for the given [identity]
     */
    @RpcMethod
    suspend fun enroll(
        requestId: String?,
        identity: ServerIdentity,
        alias: String,
        certChain: X509CertChain
    )

    /**
     * Information needed to issue a certificate.
     *
     * @property alias private key alias; it may be obfuscated if needed, it serves as a way
     *     to connect `request` and `enroll` calls
     * @property url url of the server that requested the certificate
     * @property keyAttestation key attestation for the private key (includes the public key
     *     which will be used in the certificate)
     * @property organization data for subject's X500 name `organization` part
     * @property organizationalUnit data for subject's X500 name `organizational unit` part
     * @property locality data for subject's X500 name `locality` part
     * @property stateOrProvince data for subject's X500 name `state or province` part
     * @property country data for subject's X500 name `country` part
     */
    @CborSerializable
    data class EnrollmentRequest(
        val alias: String,
        val url: String,
        val keyAttestation: KeyAttestation,
        val organization: String? = null,
        val organizationalUnit: String? = null,
        val locality: String? = null,
        val stateOrProvince: String? = null,
        val country: String? = null,
    ) {
        companion object
    }
}