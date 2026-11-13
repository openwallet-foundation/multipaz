package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant

/**
 * An implementation of [TrustManagerInterface] backed by a RICAL according to ISO/IEC 18013-5 Second Edition Annex F.
 *
 * @param signedRical the [SignedRical].
 * @param identifier an identifier for the [TrustManagerInterface].
 */
class RicalTrustManager(
    val signedRical: SignedRical,
    override val identifier: String = "default"
): TrustManagerInterface {
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()
    private val skiToIntermediateCert = mutableMapOf<String, X509Cert>()

    init {
        for (certInfo in signedRical.rical.certificateInfos) {
            val ski = certInfo.ski.toByteArray().toHex()
            if (skiToTrustPoint.containsKey(ski) || skiToIntermediateCert.containsKey(ski)) {
                Logger.w(TAG, "Ignoring certificate with SKI $ski which already exists in the RICAL")
                continue
            }
            if (!certInfo.isTrustAnchor) {
                skiToIntermediateCert[ski] = certInfo.certificate
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
                    displayName = displayName,
                    identifier = ski,
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
        atTime: Instant,
        validateCaValidity: Boolean,
        docType: String?
    ): TrustResult {
        val notAfter = signedRical.rical.notAfter
        if (notAfter != null && atTime > notAfter) {
            return TrustResult(
                isTrusted = false,
                error = IllegalStateException("RICAL is no longer valid ($atTime > $notAfter)")
            )
        }
        val expandedChain = if (skiToIntermediateCert.isEmpty() || chain.isEmpty()) {
            chain
        } else {
            buildList {
                addAll(chain)
                val visitedSkis = mutableSetOf<String>()
                while (true) {
                    val aki = last().authorityKeyIdentifier?.toHex() ?: break
                    if (skiToTrustPoint.containsKey(aki) || !visitedSkis.add(aki)) {
                        break
                    }
                    val intermediate = skiToIntermediateCert[aki] ?: break
                    if (!contains(intermediate)) {
                        add(intermediate)
                    } else {
                        break
                    }
                }
            }
        }
        return TrustManagerUtil.verifyX509TrustChain(expandedChain, atTime, skiToTrustPoint, validateCaValidity, docType)
    }

    companion object {
        private const val TAG = "RicalTrustManager"
    }
}