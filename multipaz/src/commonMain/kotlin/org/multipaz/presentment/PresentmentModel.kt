package org.multipaz.presentment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository

/**
 * A model which can be used to drive UI for presentment.
 *
 * This model is designed to be shared by a _mechanism_ (the code communicating with a credential
 * reader) and the _UI layer_ (which displays UI to the user). Typically the mechanism will also
 * include a [PromptModel] bound to the UI so things like consent prompts and authentication dialogs
 * are displayed in the UI.
 */
class PresentmentModel {

    private var mutableState = MutableStateFlow<State>(State.Reset)

    /**
     * The current state of the model.
     */
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var mutableDocumentStore: DocumentStore? = null
    private var mutableDocumentTypeRepository: DocumentTypeRepository? = null
    private var mutableDocumentsSelected = MutableStateFlow<List<Document>>(emptyList())
    private var mutableNumRequestsServed = MutableStateFlow(0)

    /**
     * The [DocumentStore] being used for presentment.
     */
    val documentStore: DocumentStore
        get() = mutableDocumentStore!!

    /**
     * The [DocumentTypeRepository] to provide information about document types.
     */
    val documentTypeRepository: DocumentTypeRepository
        get() = mutableDocumentTypeRepository!!

    /**
     * The set of [Document]s currently selected to be returned.
     */
    val documentsSelected: StateFlow<List<Document>>
        get() = mutableDocumentsSelected

    /**
     * The number of requests served from the reader.
     */
    val numRequestsServed: StateFlow<Int>
        get() = mutableNumRequestsServed.asStateFlow()

    /**
     * Resets the model.
     *
     * Should be called by the mechanism when presentment begins.
     *
     * This moves the model into the [State.Reset] state.
     *
     * @param documentStore the [DocumentStore] being used for presentment.
     * @param documentTypeRepository a [DocumentTypeRepository] to provide information about document types.
     * @param preselectedDocuments a list of documents that are preselected.
     */
    fun reset(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        preselectedDocuments: List<Document>
    ) {
        mutableState.value = State.Reset
        mutableDocumentStore = documentStore
        mutableDocumentTypeRepository = documentTypeRepository
        mutableDocumentsSelected.value = preselectedDocuments
        mutableNumRequestsServed.value = 0
    }

    /**
     * Should be called by the mechanism when connecting to the credential reader, if applicable
     *
     * This moves the model into the [State.Connecting] state.
     */
    fun setConnecting() {
        mutableState.value = State.Connecting
    }

    /**
     * Should be called by the mechanism when waiting for the credential reader to send a request.
     *
     * This moves the model into the [State.WaitingForReader] state.
     */
    fun setWaitingForReader() {
        mutableState.value = State.WaitingForReader
    }

    /**
     * Should be called by the mechanism when the user selects a particular set of documents.
     *
     * This updates [selectedDocuments].
     *
     * @param selectedDocuments the selected documents.
     */
    fun setDocumentsSelected(selectedDocuments: List<Document>) {
        mutableDocumentsSelected.value = selectedDocuments
    }

    /**
     * Should be called by the mechanism when waiting for the user to provide input (consent prompt or authentication).
     *
     * This moves the model into the [State.WaitingForUserInput] state.
     */
    fun setWaitingForUserInput() {
        mutableState.value = State.WaitingForUserInput
    }

    /**
     * Should be called by the mechanism when transmitting the response to the credential reader.
     *
     * This moves the model into the [State.Sending] state.
     */
    fun setSending() {
        mutableNumRequestsServed.value += 1
        mutableState.value = State.Sending
    }

    /**
     * Should be called by the mechanism when the transaction is complete.
     *
     * This moves the model into the [State.Completed] state.
     *
     * @param error if the transaction fails, this should be set to a non-`null` value.
     */
    fun setCompleted(error: Throwable?) {
        mutableState.value = State.Completed(error)
    }

    /**
     * Should be called by the UI layer when the user cancels the transaction.
     *
     * This moves the model into the [State.CanceledByUser] state.
     *
     * The mechanism should watch for this, cancel the transaction, and call [setCompleted] passing a
     * [PresentmentCanceled] error.
     */
    fun setCanceledByUser() {
        mutableState.value = State.CanceledByUser
    }

    /**
     * State hierarchy.
     */
    sealed class State {
        /** The presentment has just started. */
        data object Reset: State()

        /** Connecting to the credential reader. */
        data object Connecting: State()

        /** Waiting for the credential reader. */
        data object WaitingForReader: State()

        /** Waiting for user input. */
        data object WaitingForUserInput: State()

        /** Waiting to send the response to the credential reader. */
        data object Sending: State()

        /** The presentment has completed.
         *
         * @param error if `null` the transaction succeeded, otherwise a [Throwable] conveying what went wrong.
         */
        data class Completed(
            val error: Throwable?
        ): State()

        /** The user canceled the transaction from the UI. */
        data object CanceledByUser: State()
    }
}