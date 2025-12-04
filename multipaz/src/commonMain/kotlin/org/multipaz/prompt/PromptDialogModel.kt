package org.multipaz.prompt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A model for an individual prompt dialog that presents [ParametersT] to the user and
 * obtains [ResultT] response from them.
 *
 * Individual dialog types should subclass this class using appropriate types for
 * [ParametersT] and [ResultT], creating a new dialog type (by implementing [DialogType]) and
 * return it from [dialogType] property.
 *
 * Each dialog model should be registered in the [PromptModel] using
 * [PromptModel.Builder.addPromptDialogModel] and bound to UI which should collect [dialogState]
 * flow (e.g. `collectAsState` in Compose UI).
 */
abstract class PromptDialogModel<ParametersT, ResultT> {
    interface DialogType<out PromptDialogModelT: PromptDialogModel<*,*>>
    private val mutableDialogState =
        MutableStateFlow<DialogState<ParametersT, ResultT>>(NoDialogState())

    internal var owningPromptModel: PromptModel? = null

    /**
     * Dialog type that this model servers.
     *
     * This value is used as a key to find the appropriate dialog model using
     * [PromptModel.getDialogModel].
     */
    abstract val dialogType: DialogType<PromptDialogModel<ParametersT, ResultT>>

    /**
     * The state of this dialog.
     *
     * Only a single dialog of a given type can be displayed by the UI at any given time.
     */
    val dialogState: SharedFlow<DialogState<ParametersT, ResultT>>
        get() = mutableDialogState.asSharedFlow()

    /**
     * True if this model is bound to the UI.
     *
     * This is determined by detecting that [dialogState] has some subscribers.
     */
    val bound: Boolean get() = mutableDialogState.subscriptionCount.value > 0

    /**
     * A class that describes the state of the dialog.
     */
    sealed class DialogState<ParametersT, ResultT>

    /**
     * Prompt dialog should not be shown.
     * @param initial is true when the dialog was not shown yet
     */
    class NoDialogState<ParametersT, ResultT>(
        val initial: Boolean = true
    ): DialogState<ParametersT, ResultT>()

    /**
     * Prompt dialog should be displayed.
     * @param parameters are dialog-specific parameters to present/interact with the user.
     * @param resultChannel is a [SendChannel] where user response should be sent.
     */
    class DialogShownState<ParametersT, ResultT>(
        val parameters: ParametersT,
        val resultChannel: SendChannel<ResultT>
    ): DialogState<ParametersT, ResultT>()

    /**
     * Request UI to display a prompt dialog and obtain a response from the user.
     *
     * Only a single dialog for a given [PromptDialogModel] can be displayed at any given
     * time; if a dialog is already displayed it is dismissed first.
     *
     * For the prompt to be displayed, this model should be bound to the UI, which means that
     * there are some subscribers to its state [dialogState]. This can be checked by using
     * [bound] property. If UI is not bound [PromptModel.launchUi] is called to attempt to
     * launch the UI. If the model remains unbound after this call, [PromptUiNotAvailableException]
     * is thrown.
     *
     * If the coroutine that called this method is cancelled, the prompt dialog that was
     * popped up as a result of this call will be dismissed.
     *
     * @param parameters [PromptDialogModel]-specific dialog parameters
     * @param lingerDuration keep the dialog open for that long after the response is received;
     *    if the dialog is dismissed, it is removed immediately
     * @return response from the user
     * @throws PromptUiNotAvailableException if this model is not bound to the UI (see [bound])
     * @throws PromptDismissedException is the prompt is dismissed by the user or another prompt
     *     of the same kind is popped up from a different coroutine
     */
    suspend fun displayPrompt(
        parameters: ParametersT,
        lingerDuration: Duration = 0.seconds
    ): ResultT {
        if (!bound) {
            owningPromptModel!!.launchUi(this)
            if (!bound) {
                throw PromptUiNotAvailableException()
            }
        }
        val existingState = mutableDialogState.value
        if (existingState is DialogShownState) {
            // Some other coroutine called displayPrompt on this object and is waiting
            // for the response. First, change the state. This is mostly needed, so that
            // the coroutine that is currently in displayPrompt call does not try to change
            // the state itself.
            mutableDialogState.emit(NoDialogState())
            // Next, make the other active displayPrompt call throw PromptDismissedException.
            // NB: this does nothing if the channel is closed already
            existingState.resultChannel.close(PromptDismissedException())
        }
        val resultChannel = Channel<ResultT>(Channel.RENDEZVOUS)
        val dialogShownState = DialogShownState(parameters, resultChannel)
        mutableDialogState.emit(dialogShownState)
        var effectiveLingerDuration = lingerDuration
        return try {
            resultChannel.receive()
        } catch (err: PromptDismissedException) {
            // User dismissed, don't linger
            effectiveLingerDuration = 0.seconds
            throw err
        } catch (err: CancellationException) {
            // This coroutine cancelled, don't linger
            effectiveLingerDuration = 0.seconds
            throw err
        } finally {
            if (effectiveLingerDuration.isPositive() && currentCoroutineContext().isActive) {
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        delay(effectiveLingerDuration)
                    } finally {
                        hideDialog(dialogShownState)
                    }
                }
            } else {
                hideDialog(dialogShownState)
            }
        }
    }

    private suspend fun hideDialog(dialogShownState: DialogShownState<ParametersT, ResultT>) {
        // Using NonCancellable is important to change the dialog state to dismissed
        // when this coroutine is in cancelling state.
        withContext(NonCancellable) {
            // Only change the state if no one changed it yet.
            if (mutableDialogState.value === dialogShownState) {
                mutableDialogState.emit(NoDialogState(false))
            }
        }
    }
}