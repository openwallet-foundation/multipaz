package org.multipaz.verifier.session

import io.ktor.http.Url
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.storage.StorageTableSpec
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Credential verification session for the server back-end supporting `multipazVerifyCredentials`
 * JavaScript API.
 *
 * @property nonce raw nonce (base64url encoding is used where string is needed)
 * @property ephemeralPrivateKey private key used to sign the request
 * @property encryptionPrivateKey private key used for response encryption
 * @property dcqlQuery JSON-serialized DCQL query
 * @property transactionData Base64Url-encoded transaction data
 * @property result verification result (once obtained and verified)
 */
@CborSerializable
data class Session(
    val nonce: ByteString,
    val encryptionPrivateKey: EcPrivateKey,
    val dcqlQuery: String,
    val transactionData: List<String>?,
    var responseProtocol: String? = null,
    var response: ByteString? = null,
    var result: String? = null
) {
    companion object {
        /**
         * Creates a new session in the storage.
         *
         * @param dcqlQuery JSON-encoded DCQL query
         * @param transactionData Base64Url-encoded transaction data
         * @return a pair of sessionId and a [Session]
         */
        suspend fun createSession(
            dcqlQuery: String,
            transactionData: List<String>?
        ): Pair<String, Session> {
            val session = Session(
                nonce = ByteString(Random.nextBytes(15)),
                encryptionPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256),
                dcqlQuery = dcqlQuery,
                transactionData = transactionData
            )
            val id = BackendEnvironment.getTable(tableSpec).insert(
                key = null,
                data = ByteString(session.toCbor()),
                expiration = Clock.System.now() + 1.hours
            )
            return Pair(id, session)
        }

        /**
         * Returns [Session] by its sessionId loading it from the storage.
         *
         * @param sessionId session id
         * @return loaded [Session]
         */
        suspend fun getSession(sessionId: String): Session? =
            BackendEnvironment.getTable(tableSpec).get(sessionId)
                ?.let { Session.fromCbor(it.toByteArray()) }

        /**
         * Updates [Session] in the storage.
         *
         * @param sessionId session id
         * @param session updated [Session] value
         */
        suspend fun updateSession(sessionId: String, session: Session) {
            BackendEnvironment.getTable(tableSpec).update(
                key = sessionId,
                data = ByteString(session.toCbor())
            )
        }

        /**
         * Deletes a [Session] in the storage
         *
         * @param sessionId session id
         */
        suspend fun deleteSession(sessionId: String) {
            BackendEnvironment.getTable(tableSpec).delete(sessionId)
        }

        private val tableSpec = StorageTableSpec(
            name = "VerifierLightSessions",
            supportPartitions = false,
            supportExpiration = true
        )
    }
}