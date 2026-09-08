package org.multipaz.revocation

import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.AsymmetricKey.AnonymousExplicit
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Generates a simple test DS private key with certificate chain attached.
 *
 * Certificate chain always has 2 elements:
 * - the first certificate in the certificate chain is DS certificate,
 * - the second one is IACA certificate
 *
 * @return new test DS private key
 */
suspend fun createTestDsKey(): AsymmetricKey.X509Certified {
    val iacaRawKey = AsymmetricKey.ephemeral() as AnonymousExplicit
    val validFrom = Clock.System.now().truncateToWholeSeconds() - 10.seconds
    val validUntil = validFrom + 24.hours
    val iacaCert = MdocUtil.generateIacaCertificate(
        iacaKey = iacaRawKey,
        subject = X500Name.fromName("C=US,CN=IACA test key"),
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil,
        issuerAltNameUrl = "https://example.com",
        crlUrl = "https://example.com/crl"
    )
    val iacaKey = AsymmetricKey.X509CertifiedExplicit(
        certChain = X509CertChain(listOf(iacaCert)),
        privateKey = iacaRawKey.privateKey
    )
    val dsRawKey = AsymmetricKey.ephemeral() as AnonymousExplicit
    val dsCert = MdocUtil.generateDsCertificate(
        iacaKey = iacaKey,
        dsKey = dsRawKey.publicKey,
        subject = X500Name.fromName("C=US,CN=DS test key"),
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil,
    )
    return AsymmetricKey.X509CertifiedExplicit(
        certChain = X509CertChain(listOf(dsCert, iacaCert)),
        privateKey = dsRawKey.privateKey
    )
}