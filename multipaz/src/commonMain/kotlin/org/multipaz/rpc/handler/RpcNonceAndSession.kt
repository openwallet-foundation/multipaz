package org.multipaz.rpc.handler

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RpcNonceAndSession(
    val nextNonce: ByteString,
    val sessionId: String
) {
    companion object {
        private const val TAG = "RpcNonceAndSession"

        private val cipherInitLock = Mutex()

        @Volatile
        private var nonceCipher: SimpleCipher? = null

        // Poor man's database transaction. This is not going to be totally safe if multiple
        // processes are using the same database.
        private val nonceTableLock = Mutex()

        private val rpcNonceTableSpec = StorageTableSpec(
            name = "RpcAuthAssertionSession",
            supportPartitions = true,
            supportExpiration = true
        )

        private val setupTableSpec = StorageTableSpec(
            name = "RpcAuthAssertionSetup",
            supportPartitions = false,
            supportExpiration = false
        )

        /**
         * Validates a client nonce and returns the next nonce and session ID.
         *
         * @param clientId identifier of the client.
         * @param nonce current nonce sent by the client.
         * @param expiration expiration timestamp for the session.
         * @return [RpcNonceAndSession] containing the next nonce and session ID.
         * @throws RpcAuthNonceException if the nonce is empty, invalid, or out of sequence, providing a fresh nonce.
         */
        suspend fun checkNonce(
            clientId: String,
            nonce: ByteString,
            expiration: Instant
        ): RpcNonceAndSession {
            val storage = BackendEnvironment.getInterface(Storage::class)!!
            val table = storage.getTable(rpcNonceTableSpec)
            val cipher = getNonceCipher()
            if (nonce.size == 0) {
                val newNonce = nonceTableLock.withLock {
                    newSession(table, cipher, clientId, expiration)
                }
                throw RpcAuthNonceException(newNonce)
            }
            val sessionId = try {
                cipher.decrypt(nonce.toByteArray()).toBase64Url()
            } catch (err: SimpleCipher.DataTamperedException) {
                Logger.w(TAG, "Nonce decryption failed for client '$clientId', creating a new session", err)
                val newNonce = nonceTableLock.withLock {
                    newSession(table, cipher, clientId, expiration)
                }
                throw RpcAuthNonceException(newNonce)
            }
            val expectedNonce = table.get(key = sessionId, partitionId = clientId)
            val nextNonce = nonceTableLock.withLock {
                sessionNonce(table, cipher, clientId, sessionId, expiration)
            }
            if (expectedNonce != nonce) {
                throw RpcAuthNonceException(nextNonce)
            }
            return RpcNonceAndSession(
                nextNonce,
                sessionId
            )
        }

        /**
         * Validates an RPC authorization assertion and extracts the nonce and session.
         *
         * @param assertion the authorization assertion to validate.
         * @param target RPC target endpoint.
         * @param method RPC method name.
         * @param payload payload CBOR bstr.
         * @param timeout authorization validity timeout.
         * @return [RpcNonceAndSession] containing the next nonce and session ID.
         * @throws RpcAuthException if payload hash mismatch, target/method mismatch, or message expired.
         * @throws RpcAuthNonceException if a fresh nonce must be issued.
         */
        suspend fun validateAndExtractNonceAndSession(
            assertion: AssertionRpcAuth,
            target: String,
            method: String,
            payload: Bstr,
            timeout: Duration = 10.minutes,
        ): RpcNonceAndSession {
            return validateAndExtractNonceAndSession(
                assertion = assertion,
                target = target,
                method = method,
                payload = payload,
                timeout = timeout,
                nonceChecker = { clientId, nonce, expiration ->
                    checkNonce(clientId, nonce, expiration)
                }
            )
        }

        /**
         * Validates an RPC authorization assertion and extracts the nonce and session using a custom nonce checker.
         *
         * @param assertion the authorization assertion to validate.
         * @param target RPC target endpoint.
         * @param method RPC method name.
         * @param payload payload CBOR bstr.
         * @param timeout authorization validity timeout.
         * @param nonceChecker lambda to check and advance the nonce.
         * @return [RpcNonceAndSession] containing the next nonce and session ID.
         * @throws RpcAuthException if payload hash mismatch, target/method mismatch, or message expired.
         * @throws RpcAuthNonceException if a fresh nonce must be issued.
         */
        suspend fun validateAndExtractNonceAndSession(
            assertion: AssertionRpcAuth,
            target: String,
            method: String,
            payload: Bstr,
            timeout: Duration,
            nonceChecker: suspend (
                clientId: String,
                nonce: ByteString,
                expiration: Instant
            ) -> RpcNonceAndSession
        ): RpcNonceAndSession {
            val payloadHash = ByteString(Crypto.digest(Algorithm.SHA256, payload.value))
            if (payloadHash != assertion.payloadHash) {
                throw RpcAuthException(
                    message = "Payload is tempered with",
                    rpcAuthError = RpcAuthError.FAILED
                )
            }
            if (assertion.target != target || assertion.method != method) {
                throw RpcAuthException(
                    message = "RPC message is directed to a wrong target or method",
                    rpcAuthError = RpcAuthError.REQUEST_URL_MISMATCH
                )
            }
            val expiration = assertion.timestamp + timeout
            if (expiration <= Clock.System.now()) {
                throw RpcAuthException(
                    message = "Message is expired",
                    rpcAuthError = RpcAuthError.STALE
                )
            }
            return nonceChecker(assertion.clientId, assertion.nonce, expiration)
        }

        private suspend fun getNonceCipher(): SimpleCipher {
            val cipher = nonceCipher
            if (cipher != null) {
                return cipher
            }
            val storage = BackendEnvironment.getInterface(Storage::class)!!
            val table = storage.getTable(setupTableSpec)
            cipherInitLock.withLock {
                if (nonceCipher == null) {
                    var key = table.get("nonceCipherKey")
                    if (key == null) {
                        key = ByteString(Random.nextBytes(16))
                        table.insert("nonceCipherKey", key)
                    }
                    nonceCipher = AesGcmCipher(key.toByteArray())
                }
                return nonceCipher!!
            }
        }

        internal fun resetForTesting() {
            nonceCipher = null
        }

        private suspend fun newSession(
            table: StorageTable,
            cipher: SimpleCipher,
            clientId: String,
            expiration: Instant
        ): ByteString {
            val sessionId = table.insert(key = null, partitionId = clientId, data = ByteString())
            Logger.i(TAG, "New session for clientId '$clientId': '$sessionId'")
            val nonce = ByteString(cipher.encrypt(sessionId.fromBase64Url()))
            table.update(
                key = sessionId,
                partitionId = clientId,
                data = nonce,
                expiration = expiration
            )
            return nonce
        }

        private suspend fun sessionNonce(
            table: StorageTable,
            cipher: SimpleCipher,
            clientId: String,
            sessionId: String,
            expiration: Instant
        ): ByteString {
            val nonce = ByteString(cipher.encrypt(sessionId.fromBase64Url()))
            table.delete(key = sessionId, partitionId = clientId)
            table.insert(
                key = sessionId,
                partitionId = clientId,
                data = nonce,
                expiration = expiration
            )
            return nonce
        }
    }
}