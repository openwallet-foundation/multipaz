package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant

/**
 * An implementation of [TrustManager] backed by a RICAL according to ISO/IEC 18013-5 Second Edition Annex F.
 *
 * @param signedRical the [SignedRical].
 * @param identifier an identifier for the [TrustManager].
 */
class RicalTrustManager(
    val signedRical: SignedRical,
    override val identifier: String = "default"
): TrustManager {
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()

    init {
        for (certInfo in signedRical.rical.certificateInfos) {
            val ski = certInfo.ski.toByteArray().toHex()
            if (skiToTrustPoint.containsKey(ski)) {
                Logger.w(TAG, "Ignoring certificate with SKI $ski which already exists in the RICAL")
                continue
            }
            // TODO: Would be nice if there was generally a better way to get displayName,
            //   displayIcon, and privacyPolicyUrl. Could originate from many sources, including
            //   X.509 extensions, application-provided database, etc etc
            //
            val displayName = certInfo.name ?: certInfo.certificate.subject.name
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
        return TrustManagerUtil.verifyX509TrustChain(chain, atTime, skiToTrustPoint)
    }

    companion object {
        private const val TAG = "RicalTrustManager"
    }
}