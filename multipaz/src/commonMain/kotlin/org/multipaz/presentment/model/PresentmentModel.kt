package org.multipaz.presentment.model

import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.prompt.PromptModel
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * A model used for credential presentment.
 *
 * This model implements the entire UX/UI flow related to presentment, including
 *
 * - Allowing the user to cancel at any time, including when the connection is being established.
 * - Querying the user for which document to present, if multiple credentials can satisfy the request.
 * - Showing a consent dialog.
 * - Generating the response and sending it to the verifier, via the selected mechanism.
 * - Support for multiple requests if both sides keep the connection open
 */
class PresentmentModel {
    companion object {
        private const val TAG = "PresentmentModel"
    }

    /**
     * Possible states that the model can be in.
     */
    enum class State {
        /**
         * Presentment is not active.
         */
        IDLE,

        /**
         * Presentment has been started but the mechanism used to communicate with the reader is not yet available.
         */
        CONNECTING,

        /**
         * Presentment is ready, waiting for a [PresentmentSource] to be connected.
         */
        WAITING_FOR_SOURCE,

        /**
         * Presentment is currently underway.
         */
        PROCESSING,

        /**
         * A request has been received and the user needs to provide consent.
         *
         * The UI layer should show a document selection dialog with the credentials in [consentData]
         * and call [consentObtained] with the user's choice, if any.
         */
        WAITING_FOR_CONSENT,

        /**
         * Presentment is complete. If something went wrong the [error] property is set.
         */
        COMPLETED,
    }

    private val _state = MutableStateFlow<State>(State.IDLE)
    /**
     * The current state.
     */
    val state = _state.asStateFlow()

    private var _presentmentScope: CoroutineScope? = null
    /**
     * A [CoroutineScope] for the presentment process.
     *
     * Any coroutine launched in this scope will be automatically canceled when presentment completes.
     *
     * This should only be read in states which aren't [State.IDLE] and [State.COMPLETED]. It will throw
     * [IllegalStateException] if this is not the case.
     */
    val presentmentScope: CoroutineScope
        get() {
            check(_presentmentScope != null)
            check(_state.value != State.IDLE && _state.value != State.COMPLETED)
            return _presentmentScope!!
        }

    private var _mechanism: PresentmentMechanism? = null
    /**
     * The mechanism being used to communicate with the credential reader.
     */
    val mechanism: PresentmentMechanism?
        get() = _mechanism

    private var _source: PresentmentSource? = null
    /**
     * The source which provides data to present.
     */
    val source: PresentmentSource?
        get() = _source

    private var _error: Throwable? = null
    /**
     * If presentment fails, this will be set with a [Throwable] with more information about the failure.
     */
    val error: Throwable?
        get() = _error

    var promptModel: PromptModel? = null
        private set
    /**
     * Resets the model to [State.IDLE].
     */
    fun reset() {
        _mechanism?.close()
        _mechanism = null
        _source = null
        _error = null
        _dismissable.value = true
        _numRequestsServed.value = 0
        _presentmentScope?.cancel(CancellationException("PresentationModel reset"))
        _presentmentScope = null
        _state.value = State.IDLE
    }

    /**
     * Provides [PromptModel], required if presentment involves popping up any prompts, such
     * as biometrics or passphrase.
     */
    fun setPromptModel(promptModel: PromptModel) {
        this.promptModel = promptModel
    }

    /**
     * Sets the model to [State.CONNECTING].
     */
    fun setConnecting() {
        check(_state.value == State.IDLE)
        val coroutineContext = if (promptModel == null) {
            Dispatchers.Main
        } else {
            Dispatchers.Main + promptModel!!
        }
        _presentmentScope = CoroutineScope(coroutineContext)
        _state.value = State.CONNECTING
    }

    /**
     * Sets the [PresentmentMechanism] to use.
     *
     * This sets the model to [State.WAITING_FOR_SOURCE].
     *
     * @param mechanism the [PresentmentMechanism] to use.
     */
    fun setMechanism(mechanism: PresentmentMechanism) {
        check(_state.value == State.CONNECTING)
        _mechanism = mechanism
        _state.value = State.WAITING_FOR_SOURCE
    }

    /**
     * Sets the [PresentmentSource] to use as the source of truth for presentment.
     *
     * This sets the model to [State.PROCESSING].
     *
     * @param source the [PresentmentSource] to use.
     */
    fun setSource(source: PresentmentSource) {
        check(_state.value == State.WAITING_FOR_SOURCE)
        _source = source
        _state.value = State.PROCESSING

        // OK, now that we got both a mechanism and a source we're off to the races and we can
        // start the presentment flow! Do this in a separate coroutine.
        //
        _presentmentScope!!.launch {
            startPresentmentFlow()
        }
    }

    /**
     * Sets the model to [State.COMPLETED]
     *
     * @param error pass a [Throwable] if the presentation failed, `null` if successful.
     */
    fun setCompleted(error: Throwable? = null) {
        if (_state.value == State.COMPLETED) {
            Logger.w(TAG, "Already completed, ignoring second call")
            return
        }
        _mechanism?.close()
        _mechanism = null
        _error = error
        if (error != null) {
            Logger.e(TAG, "Error presenting", error)
        }
        _state.value = State.COMPLETED
        // TODO: Hack to ensure that [state] collectors (using [presentationScope]) gets called for State.COMPLETED
        _presentmentScope?.launch {
            delay(1.seconds)
            _presentmentScope?.cancel(CancellationException("PresentationModel completed"))
            _presentmentScope = null
        }
    }

    /**
     * Three different ways the close/dismiss button can be triggered.
     *
     * This is used by [MdocPresentmentMechanism] to perform either normal session-termination (normal click),
     * transport-specific session termination (long press), or termination without notifying the other end (double
     * click).
     *
     * @property CLICK the user clicked the button normally.
     * @property LONG_CLICK the user performed a long press on the button.
     * @property DOUBLE_CLICK the user double-clicked the button.
     */
    enum class DismissType {
        CLICK,
        LONG_CLICK,
        DOUBLE_CLICK,
    }

    private var _dismissable = MutableStateFlow<Boolean>(true)
    /**
     * Returns whether the presentment can be dismissed/canceled.
     *
     * If this is true the UI layer should include e.g. a button the user can press to dismiss/cancel and
     * call [dismiss] when the user clicks the button.
     */
    val dismissable = _dismissable.asStateFlow()

    private var _numRequestsServed = MutableStateFlow<Int>(0)
    /**
     * Number of requests served.
     */
    val numRequestsServed = _numRequestsServed.asStateFlow()

    /**
     * Should be called by the UI layer if the user hits the dismiss button.
     *
     * This ends the presentment flow by calling [setCompleted] with the error parameter set to
     * a [PresentmentCanceled] instance.
     *
     * @param dismissType the type of interaction the user had with the dismiss button
     */
    fun dismiss(dismissType: DismissType) {
        val mdocMechanism = mechanism as? MdocPresentmentMechanism
        if (mdocMechanism != null) {
            _presentmentScope!!.launch {
                try {
                    when (dismissType) {
                        DismissType.CLICK -> {
                            mdocMechanism.transport.sendMessage(
                                SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                            )
                            mdocMechanism.transport.close()
                        }
                        DismissType.LONG_CLICK -> {
                            mdocMechanism.transport.sendMessage(byteArrayOf())
                            mdocMechanism.transport.close()
                        }
                        DismissType.DOUBLE_CLICK -> {
                            mdocMechanism.transport.close()
                        }
                    }
                } catch (error: Throwable) {
                    Logger.e(TAG, "Caught exception closing transport", error)
                    error.printStackTrace()
                }
            }
        }
        setCompleted(PresentmentCanceled("The presentment was canceled by the user"))
    }

    private suspend fun startPresentmentFlow() {
        try {
            when (mechanism!!) {
                is MdocPresentmentMechanism -> {
                    mdocPresentment(
                        documentTypeRepository = source!!.documentTypeRepository,
                        source = source!!,
                        mechanism = mechanism as MdocPresentmentMechanism,
                        dismissable = _dismissable,
                        numRequestsServed = _numRequestsServed,
                        showConsentPrompt = { presentmentData, preselectedDocuments, requester, trustPoint ->
                            showConsentPrompt(presentmentData, preselectedDocuments, requester, trustPoint)
                        },
                    )
                }
                is DigitalCredentialsPresentmentMechanism -> {
                    digitalCredentialsPresentment(
                        documentTypeRepository = source!!.documentTypeRepository,
                        source = source!!,
                        mechanism = mechanism as DigitalCredentialsPresentmentMechanism,
                        dismissable = _dismissable,
                        showConsentPrompt = { presentmentData, preselectedDocuments, requester, trustPoint ->
                            showConsentPrompt(presentmentData, preselectedDocuments, requester, trustPoint)
                        },
                    )
                }
                is UriSchemePresentmentMechanism -> {
                    uriSchemePresentment(
                        documentTypeRepository = source!!.documentTypeRepository,
                        source = source!!,
                        mechanism = mechanism as UriSchemePresentmentMechanism,
                        dismissable = _dismissable,
                        showConsentPrompt = { presentmentData, preselectedDocuments, requester, trustPoint ->
                            showConsentPrompt(presentmentData, preselectedDocuments, requester, trustPoint)
                        },
                    )
                }
                else -> throw IllegalStateException("Unsupported mechanism $mechanism")
            }
        } catch (e: Throwable) {
            setCompleted(e)
            return
        }
        setCompleted()
    }

    private var consentContinuation: CancellableContinuation<CredentialPresentmentSelection?>? = null

    private suspend fun showConsentPrompt(
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ): CredentialPresentmentSelection? {
        check(_state.value == State.PROCESSING)
        _consentData = ConsentData(
            credentialPresentmentData = credentialPresentmentData,
            preselectedDocuments = preselectedDocuments,
            requester = requester,
            trustPoint = trustPoint,
            dynamicMetadataResolver = _source!!.dynamicMetadataResolver
        )
        _state.value = State.WAITING_FOR_CONSENT
        val ret = suspendCancellableCoroutine { continuation ->
            consentContinuation = continuation
        }
        _consentData = null
        _state.value = State.PROCESSING
        return ret
    }

    /**
     * Data to include in the consent prompt.
     *
     * @property credentialPresentmentData an object containing the credential sets that the user can choose from.
     * @property preselectedDocuments a list of pre-selected documents or empty if the user didn't preselect.
     * @property requester who made the request.
     * @property trustPoint a [TrustPoint] if the requester is trusted, null otherwise.
     */
    data class ConsentData(
        val credentialPresentmentData: CredentialPresentmentData,
        val preselectedDocuments: List<Document>,
        val requester: Requester,
        val trustPoint: TrustPoint?,
        val dynamicMetadataResolver: (requester: Requester) -> TrustMetadata? = { chain -> null },
    )

    private var _consentData: ConsentData? = null
    /**
     * Data which should be displayed in a consent prompt.
     *
     * This can only be read when in state [State.WAITING_FOR_CONSENT].
     */
    val consentData: ConsentData
        get() {
            check(_state.value == State.WAITING_FOR_CONSENT)
            return _consentData!!
        }

    /**
     * The UI layer should call this when a user has selected a document.
     *
     * This can only be called in state [State.WAITING_FOR_CONSENT]
     *
     * @param selection The selection the user made and consented to or `null` if the user canceled.
     */
    fun consentObtained(selection: CredentialPresentmentSelection?) {
        check(_state.value == State.WAITING_FOR_CONSENT)
        consentContinuation!!.resume(selection)
    }
}