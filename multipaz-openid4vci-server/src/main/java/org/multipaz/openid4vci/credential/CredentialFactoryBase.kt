package org.multipaz.openid4vci.credential

import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.SigningKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources

/**
 * Common parts of [CredentialFactory] implementation.
 */
internal abstract class CredentialFactoryBase: CredentialFactory {

    protected lateinit var signingKey: SigningKey.X509Certified

    override val signingCertificateChain: X509CertChain get() = signingKey.certChain

    final override suspend fun initialize() {
        val resources = BackendEnvironment.getInterface(Resources::class)!!
        val cert = X509Cert.fromPem(resources.getStringResource("ds_certificate.pem")!!)
        // TODO: move to configuration
        signingKey = SigningKey.X509CertifiedExplicit(
            privateKey = EcPrivateKey.fromPem(
                pemEncoding = resources.getStringResource("ds_private_key.pem")!!,
                publicKey = cert.ecPublicKey
            ),
            certChain = X509CertChain(listOf(cert))
        )
    }
}