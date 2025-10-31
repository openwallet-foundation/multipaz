package org.multipaz.server

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.cache
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.StorageTableSpec

suspend fun BackendEnvironment.Companion.getBaseUrl(): String =
    getInterface(Configuration::class)!!.baseUrl

suspend fun BackendEnvironment.Companion.getDomain(): String =
    Url(getBaseUrl()).protocolWithAuthority

/**
 * Server identity is a pair of private key and certificate chain for the corresponding
 * public key.
 */
suspend fun BackendEnvironment.Companion.getServerIdentity(
    name: String,
    fallback: suspend () -> AsymmetricKey.X509CertifiedExplicit? = { null }
): AsymmetricKey =
    cache(AsymmetricKey::class, name) { _, _ -> readServerIdentity(name, fallback) }

suspend fun BackendEnvironment.Companion.readServerIdentity(
    name: String,
    fallback: suspend () -> AsymmetricKey.X509CertifiedExplicit? = { null }
): AsymmetricKey {
    // Read configuration
    val identityString = getInterface(Configuration::class)!!.getValue(name)
    if (identityString != null) {
        try {
            val identity = Json.parseToJsonElement(identityString).jsonObject
            return if (identity.containsKey("jwk")) {
                // Old way - for compatibility
                val jwk = identity["jwk"]!!.jsonObject
                val x5c = identity["x5c"]!!.jsonArray
                AsymmetricKey.X509CertifiedExplicit(
                    privateKey = EcPrivateKey.fromJwk(jwk),
                    certChain = X509CertChain.fromX5c(x5c)
                )
            } else {
                val secureAreaRepository = getInterface(SecureAreaRepository::class)
                AsymmetricKey.parse(identity, secureAreaRepository)
            }
        } catch (err: Exception) {
            throw IllegalStateException("Invalid '$name' configuration", err)
        }
    }

    // Read the database
    identityLock.withLock {
        val table = getTable(serverIdentityTableSpec)
        val blob = table.get(name)
        if (blob != null) {
            val cbor = Cbor.decode(blob.toByteArray())
            return AsymmetricKey.X509CertifiedExplicit(
                privateKey = EcPrivateKey.fromDataItem(cbor["privateKey"]),
                certChain = X509CertChain.fromDataItem(cbor["certificateChain"])
            )
        }
        val fallbackIdentity = fallback.invoke()
            ?: throw IllegalStateException("Missing '$name' configuration and there is no fallback")

        val cbor = buildCborMap {
            put("privateKey", fallbackIdentity.privateKey.toDataItem())
            put("certificateChain", fallbackIdentity.certChain.toDataItem())
        }
        table.insert(key = name, data = ByteString(Cbor.encode(cbor)))
        return fallbackIdentity
    }
}

private val identityLock = Mutex()

private val serverIdentityTableSpec = StorageTableSpec(
    name = "ServerIdentity",
    supportPartitions = false,
    supportExpiration = false
)