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
import kotlin.experimental.xor
import kotlin.math.min
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

        suspend fun checkNonce(
            clientId: String,
            nonce: ByteString,
            expiration: Instant
        ): RpcNonceAndSession {
            val storage = BackendEnvironment.getInterface(Storage::class)!!
            val table = storage.getTable(rpcNonceTableSpec)
            val cipher = getNonceCipher(clientId)
            if (nonce.size == 0) {
                val newNonce = nonceTableLock.withLock {
                    newSession(table, cipher, clientId, expiration)
                }
                throw RpcAuthNonceException(newNonce)
            }
            val sessionId = try {
                cipher.decrypt(nonce.toByteArray()).toBase64Url()
            } catch (_: SimpleCipher.DataTamperedException) {
                // Decryption failed. This is a fake nonce, not merely slate nonce!
                throw RpcAuthException("Invalid nonce", RpcAuthError.FAILED)
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

        suspend fun validateAndExtractNonceAndSession(
            assertion: AssertionRpcAuth,
            target: String,
            method: String,
            payload: Bstr,
            timeout: Duration = 10.minutes,
            nonceChecker: suspend (
                clientId: String,
                nonce: ByteString,
                expiration: Instant
            ) -> RpcNonceAndSession = ::checkNonce
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

        private suspend fun getNonceCipher(clientId: String): SimpleCipher {
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
                    val keyBytes = key.toByteArray()
                    val pad = clientId.encodeToByteArray()
                    for (i in 0..<min(keyBytes.size, pad.size)) {
                        keyBytes[i] = keyBytes[i] xor pad[i]
                    }
                    nonceCipher = AesGcmCipher(keyBytes)
                }
                return nonceCipher!!
            }
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