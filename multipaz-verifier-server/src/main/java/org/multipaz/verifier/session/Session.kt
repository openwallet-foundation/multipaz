package org.multipaz.verifier.session

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.verifier.customization.VerifierResponse
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Credential verification session for the server back-end supporting `multipazVerifyCredentials`
 * JavaScript API.
 *
 * @property nonce raw nonce (base64url encoding is used where string is needed)
 * @property encryptionPrivateKey private key used for response encryption
 * @property dcqlQuery JSON-serialized DCQL query
 * @property jsonTransactionData JSON transaction data
 * @property responseProtocol protocol that was used for response prefixed by "dcapi:" or
 *  "custom-url:"
 * @param response raw response from the presentment
 * @property result verification result (once obtained and verified) as serialized JSON,
 *  see [VerifierResponse.response] for more info
 */
@CborSerializable
data class Session(
    val nonce: ByteString,
    val encryptionPrivateKey: EcPrivateKey,
    val dcqlQuery: String,
    val jsonTransactionData: List<String>?,
    var responseProtocol: String? = null,
    var response: ByteString? = null,
    var result: String? = null
) {
    companion object {
        /**
         * Creates a new session in the storage.
         *
         * @param dcqlQuery JSON-encoded DCQL query
         * @param jsonTransactionData Base64Url-encoded transaction data
         * @return a pair of sessionId and a [Session]
         */
        suspend fun createSession(
            dcqlQuery: String,
            jsonTransactionData: List<String>?,
        ): Pair<String, Session> {
            val session = Session(
                nonce = ByteString(Random.nextBytes(15)),
                encryptionPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256),
                dcqlQuery = dcqlQuery,
                jsonTransactionData = jsonTransactionData,
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