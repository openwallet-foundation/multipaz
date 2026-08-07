package org.multipaz.revocation

import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Cbor
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.trustmanagement.TrustManagerInterface
import kotlin.time.Clock

/**
 * Information needed to perform a revocation check for a credential or a credential presentation.
 *
 * @property revocationStatus revocation status from the credential
 * @property certificate top-level certificate that should be used to check revocation data
 *  signature; may or may not be needed (as certificate might be embedded in [revocationStatus]
 *  itself)
 */
data class RevocationInfo(
    val revocationStatus: RevocationStatus,
    val certificate: X509Cert?
)

/**
 * Convenience method to extract [RevocationInfo] from a [Credential].
 *
 * Note: [RevocationInfo.certificate] can be successfully obtained only when the credential
 * is issued by an issuer that is trusted by [trustManager].
 *
 * @param trustManager trust manager that is searched for an issuer's root certificate
 * @return [RevocationInfo] for this credential
 */
suspend fun Credential.getRevocationInfo(trustManager: TrustManagerInterface): RevocationInfo? {
    return when (this) {
        is MdocCredential -> {
            val issuerSigned = Cbor.decode(issuerProvidedData.toByteArray())
            val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
            val certChain = issuerAuth.unprotectedHeaders[
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
            ]!!.asX509CertChain.certificates
            val trustResult = trustManager.verify(certChain, mso.validFrom)
            mso.revocationStatus?.let { RevocationInfo(it, trustResult.trustChain?.certificates?.last()) }
        }
        is SdJwtVcCredential -> {
            val sdjwt = SdJwt.fromCompactSerialization(issuerProvidedData.decodeToString())
            val certChain = sdjwt.x5c ?: return null
            val trustResult = trustManager.verify(certChain.certificates, sdjwt.validFrom ?: Clock.System.now())
            sdjwt.revocationStatus?.let { RevocationInfo(it, trustResult.trustChain?.certificates?.last()) }
        }
        else -> null
    }
}
