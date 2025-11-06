package org.multipaz.openid4vci.credential

import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.server.getBaseUrl
import org.multipaz.server.getServerIdentity
import kotlin.time.Clock

/**
 * Common parts of [CredentialFactory] implementation.
 */
internal abstract class CredentialFactoryBase: CredentialFactory {

    override lateinit var signingKey: AsymmetricKey.X509Certified

    final override suspend fun initialize() {
        signingKey = BackendEnvironment.getServerIdentity("ds_jwk") {
            val configuration = BackendEnvironment.getInterface(Configuration::class)!!
            val secureAreaRepository = BackendEnvironment.getInterface(SecureAreaRepository::class)
            val iacaKey = AsymmetricKey.parse(
                json = configuration.getValue("iaca_jwk")!!,
                secureAreaRepository = secureAreaRepository
            ) as AsymmetricKey.X509Certified
            val iacaCert = iacaKey.certChain.certificates.first()
            val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = iacaKey,
                dsKey = dsPrivateKey.publicKey,
                subject = X500Name.fromName("CN=${BackendEnvironment.getBaseUrl()}"),
                serial = ASN1Integer(Clock.System.now().epochSeconds),
                validFrom = iacaCert.validityNotBefore,
                validUntil = iacaCert.validityNotAfter
            )
            AsymmetricKey.X509CertifiedExplicit(
                privateKey = dsPrivateKey,
                certChain = X509CertChain(listOf(dsCert) + iacaKey.certChain.certificates)
            )
        } as AsymmetricKey.X509Certified
    }
}