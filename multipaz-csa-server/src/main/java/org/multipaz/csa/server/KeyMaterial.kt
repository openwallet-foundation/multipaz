package org.multipaz.csa.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.server.common.persistentServerKey

/**
 * Various keys used by the Cloud Secure Area.
 *
 * This is initialized on the first server start and saved in the database. On subsequent server
 * runs, it is read from the database.
 */
data class KeyMaterial(
    val serverSecureAreaBoundKey: ByteString,
    val attestationKey: AsymmetricKey.X509Certified,
    val cloudBindingKey: AsymmetricKey.X509Certified
) {
    companion object {
        fun create(backendEnvironment: Deferred<BackendEnvironment>): Deferred<KeyMaterial> {
            return CoroutineScope(Dispatchers.Default).async {
                withContext(backendEnvironment.await()) {
                    val attestationSigningKey = getServerIdentity(ServerIdentity.KEY_ATTESTATION)
                    val bindingKey = getServerIdentity(ServerIdentity.CLOUD_SECURE_AREA_BINDING)
                    KeyMaterial(
                        attestationKey = attestationSigningKey,
                        cloudBindingKey = bindingKey,
                        serverSecureAreaBoundKey = persistentServerKey(name = "csa")
                    )
                }
            }
        }
    }
}

