package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.RicalCertificateInfo
import org.multipaz.mdoc.rical.RicalTrustConstraint
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant

/**
 * An implementation of [TrustManagerInterface] backed by a RICAL according to ISO/IEC 18013-5 Second Edition Annex F.
 *
 * @param signedRical the [SignedRical].
 * @param identifier an identifier for the [TrustManagerInterface].
 * @param trustConstraintEvaluator optional callback to evaluate non-empty [RicalTrustConstraint] entries on the first
 *   (bottom-up) matching CA certificate in the validated trust chain per Annex F.3.2.7. When `null`, any non-empty
 *   [RicalTrustConstraint] is treated as unrecognized and therefore not satisfied.
 */
class RicalTrustManager(
    val signedRical: SignedRical,
    override val identifier: String = "default",
    val trustConstraintEvaluator: ((constraint: RicalTrustConstraint, chain: List<X509Cert>) -> Boolean)? = null
): TrustManagerInterface {
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()
    private val skiToCertInfo = mutableMapOf<String, RicalCertificateInfo>()

    init {
        for (certInfo in signedRical.rical.certificateInfos) {
            val ski = certInfo.ski.toByteArray().toHex()
            if (skiToTrustPoint.containsKey(ski)) {
                Logger.w(TAG, "Ignoring certificate with SKI $ski which already exists in the RICAL")
                continue
            }
            val displayName = certInfo.name ?: certInfo.certificate.subject.name
            skiToTrustPoint[ski] = TrustPoint(
                certificate = certInfo.certificate,
                metadata = TrustMetadata(
                    displayName = displayName,
                    identifier = ski,
                ),
                trustManager = this
            )
            skiToCertInfo[ski] = certInfo
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
        if (atTime < signedRical.rical.date) {
            return TrustResult(
                isTrusted = false,
                error = IllegalStateException("RICAL date is in the future ($atTime < ${signedRical.rical.date})")
            )
        }
        val notAfter = signedRical.rical.notAfter
        if (notAfter != null && atTime > notAfter) {
            return TrustResult(
                isTrusted = false,
                error = IllegalStateException("RICAL is no longer valid ($atTime > $notAfter)")
            )
        }
        val result = TrustManagerUtil.verifyX509TrustChain(
            chain = chain,
            atTime = atTime,
            skiToTrustPoint = skiToTrustPoint,
            validateCaValidity = validateCaValidity,
            docType = docType
        )
        if (!result.isTrusted) {
            return result
        }
        val completeChain = result.trustChain?.certificates ?: chain
        val firstMatchingCertInfo = completeChain.firstNotNullOfOrNull { cert ->
            cert.subjectKeyIdentifier?.toHex()?.let { skiToCertInfo[it] }
        }
        if (firstMatchingCertInfo != null && firstMatchingCertInfo.trustConstraints.isNotEmpty()) {
            val isSatisfied = firstMatchingCertInfo.trustConstraints.any { constraint ->
                if (constraint.extensions.isEmpty() && constraint.otherInfo.isEmpty()) {
                    true
                } else {
                    trustConstraintEvaluator?.invoke(constraint, completeChain) == true
                }
            }
            if (!isSatisfied) {
                return result.copy(
                    isTrusted = false,
                    error = IllegalStateException(
                        "None of the RICAL trustConstraints were satisfied for CA " +
                            "'${firstMatchingCertInfo.certificate.subject.name}'"
                    )
                )
            }
        }
        return result
    }

    companion object {
        private const val TAG = "RicalTrustManager"
    }
}