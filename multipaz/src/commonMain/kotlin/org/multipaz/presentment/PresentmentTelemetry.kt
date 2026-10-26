package org.multipaz.presentment

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.TimeoutCancellationException
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.nfc.MdocNfcEngagementException
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportStats
import org.multipaz.mdoc.transport.TransportWarning

/**
 * Protocol used for credential presentment.
 */
enum class PresentmentProtocol {
    /** ISO/IEC 18013-5 proximity presentment (QR or NFC engagement over BLE/NFC/Hybrid). */
    ISO_18013_5_PROXIMITY,

    /** ISO/IEC 18013-7 Annex A remote mdoc presentment. */
    ISO_18013_7_ANNEX_A,

    /** ISO/IEC 18013-7 Annex C (W3C Digital Credentials API using `org-iso-mdoc` protocol). */
    ISO_18013_7_ANNEX_C,

    /** W3C Digital Credentials API using `openid4vp` protocol. */
    W3C_DC_OPENID4VP,

    /** OpenID4VP over custom URI scheme (`openid4vp://` or similar). */
    URI_SCHEME_OPENID4VP,
}

/**
 * Outcome of a presentment session.
 */
enum class PresentmentOutcome {
    /** The presentment session completed successfully. */
    SUCCESS,

    /** The user canceled the consent prompt. */
    CANCELED_BY_USER,

    /** No matching credentials in the wallet could satisfy the request. */
    CANNOT_SATISFY_REQUEST,

    /** User authentication (such as biometric or passphrase verification) failed or was canceled. */
    AUTHENTICATION_FAILED,

    /** Facial matching failed or was canceled. */
    FACE_NOT_MATCHED,

    /** NFC engagement failed before handover completed. */
    NFC_ENGAGEMENT_FAILED,

    /** Timed out waiting for the transport connection to be established. */
    CONNECTION_TIMEOUT,

    /** Timed out waiting for a request message from the reader. */
    REQUEST_TIMEOUT,

    /** A transport error or unexpected disconnection occurred. */
    TRANSPORT_ERROR,

    /** An uncategorized error occurred. */
    OTHER_ERROR,
}

/**
 * Anonymized, PII-free telemetry collected during a credential presentment session.
 *
 * This data class deliberately excludes any personally identifiable information (such as credential
 * identifiers, claim values, reader certificate subjects, origins, or session transcripts) so that
 * it can be stored in [org.multipaz.eventlogger.EventLogger] and safely aggregated or transmitted
 * to a wallet provider's telemetry server.
 *
 * @property protocol the presentment protocol used.
 * @property engagementType the engagement channel type for proximity presentment, if applicable.
 * @property outcome the outcome of the presentment.
 * @property warnings structured warnings collected during presentment and transport execution.
 * @property requesterInTrustList whether the requester was authenticated against a known trust list, or `null` if unauthenticated/not applicable.
 * @property requestSizeBytes size in bytes of the incoming request payload, if received.
 * @property responseSizeBytes size in bytes of the generated response payload, if generated.
 * @property numRequestsServed number of requests served during the session.
 * @property numDocumentsPresented total number of documents presented across all served requests.
 * @property numNfcReTaps number of NFC re-taps that occurred during the session.
 * @property durationEngagementToConnected time elapsed from start of presentment until transport reached connected state.
 * @property durationConnectedToRequestReceived time elapsed from transport connected state until the first request was received.
 * @property durationUserConsentAndAuth time spent obtaining user consent, unlocking keys, and performing face matching.
 * @property durationResponseGeneration time spent generating and signing the credential response.
 * @property durationResponseTransmission time spent transmitting the response over the transport or HTTP endpoint.
 * @property durationTotal total duration of the presentment session.
 * @property transportStats underlying [MdocTransportStats] for proximity transports, if applicable.
 */
@CborSerializable
data class PresentmentTelemetry(
    val protocol: PresentmentProtocol,
    val engagementType: EngagementType? = null,
    val outcome: PresentmentOutcome,
    val warnings: List<TransportWarning> = emptyList(),
    val requesterInTrustList: Boolean? = null,
    val requestSizeBytes: Int? = null,
    val responseSizeBytes: Int? = null,
    val numRequestsServed: Int = 0,
    val numDocumentsPresented: Int = 0,
    val numNfcReTaps: Int = 0,
    val durationEngagementToConnected: Duration? = null,
    val durationConnectedToRequestReceived: Duration? = null,
    val durationUserConsentAndAuth: Duration? = null,
    val durationResponseGeneration: Duration? = null,
    val durationResponseTransmission: Duration? = null,
    val durationTotal: Duration? = null,
    val transportStats: MdocTransportStats? = null,
) {
    companion object
}

/**
 * Maps a [Throwable] thrown during presentment to a structured [PresentmentOutcome].
 *
 * @param error the exception thrown during presentment or connection setup.
 * @param waitingForConnection `true` if the exception occurred while advertising or waiting for a
 *   transport connection before entering presentment.
 */
fun classifyPresentmentException(
    error: Throwable,
    waitingForConnection: Boolean = false,
): PresentmentOutcome {
    return when (error) {
        is PresentmentCanceledException -> PresentmentOutcome.CANCELED_BY_USER
        is PresentmentCannotSatisfyRequestException -> PresentmentOutcome.CANNOT_SATISFY_REQUEST
        is AuthenticationFailedException -> PresentmentOutcome.AUTHENTICATION_FAILED
        is FaceNotMatchedException -> PresentmentOutcome.FACE_NOT_MATCHED
        is MdocNfcEngagementException -> PresentmentOutcome.NFC_ENGAGEMENT_FAILED
        is Iso18013PresentmentTimeoutException -> PresentmentOutcome.REQUEST_TIMEOUT
        is PresentmentTimeout,
        is TimeoutCancellationException -> PresentmentOutcome.CONNECTION_TIMEOUT
        is Iso18013PresentmentNfcDisconnectedException,
        is MdocTransportClosedException,
        is MdocTransportException -> PresentmentOutcome.TRANSPORT_ERROR
        is CancellationException -> {
            val nonCancelCause = generateSequence(error.cause) { it.cause }
                .firstOrNull { it !is CancellationException || it is TimeoutCancellationException }
            if (nonCancelCause != null) {
                classifyPresentmentException(nonCancelCause, waitingForConnection)
            } else {
                PresentmentOutcome.CANCELED_BY_USER
            }
        }
        else -> if (waitingForConnection) {
            PresentmentOutcome.TRANSPORT_ERROR
        } else {
            PresentmentOutcome.OTHER_ERROR
        }
    }
}
