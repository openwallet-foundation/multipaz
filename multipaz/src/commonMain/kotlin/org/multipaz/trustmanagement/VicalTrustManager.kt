package org.multipaz.trustmanagement

import kotlin.time.Instant
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.util.Logger
import org.multipaz.util.toHex

/**
 * An implementation of [TrustManagerInterface] backed by a VICAL according to ISO/IEC 18013-5 Annex C.
 *
 * @param signedVical the [SignedVical].
 * @param identifier an identifier for the [TrustManagerInterface].
 */
class VicalTrustManager(
    val signedVical: SignedVical,
    override val identifier: String = "default"
): TrustManagerInterface {
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()

    init {
        for (certInfo in signedVical.vical.certificateInfos) {
            val ski = certInfo.ski.toByteArray().toHex()
            if (skiToTrustPoint.containsKey(ski)) {
                Logger.w(TAG, "Ignoring certificate with SKI $ski which already exists in the VICAL")
                continue
            }
            // TODO: Would be nice if there was generally a better way to get displayName,
            //   displayIcon, and privacyPolicyUrl. Could originate from many sources, including
            //   X.509 extensions, application-provided database, etc etc
            //
            val displayName = certInfo.issuingAuthority ?: certInfo.certificate.subject.name
            skiToTrustPoint[ski] = TrustPoint(
                certificate = certInfo.certificate,
                metadata = TrustMetadata(
                    displayName = displayName
                ),
                trustManager = this
            )
        }

    }

    override suspend fun getTrustPoints(): List<TrustPoint> {
        return skiToTrustPoint.values.toList()
    }

    override suspend fun verify(
        chain: List<X509Cert>,
        atTime: Instant
    ): TrustResult {
        // TODO: Need a way to return list of doctypes in TrustResult...
        return TrustManagerUtil.verifyX509TrustChain(chain, atTime, skiToTrustPoint)
    }

    companion object {
        private const val TAG = "VicalTrustManager"
    }
}