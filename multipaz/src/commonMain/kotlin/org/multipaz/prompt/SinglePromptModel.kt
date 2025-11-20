package org.multipaz.prompt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A model for an individual prompt dialog.
 */
class SinglePromptModel<ParametersT, ResultT> {
    private val mutableDialogState = MutableSharedFlow<DialogState<ParametersT, ResultT>>()

    val dialogState: SharedFlow<DialogState<ParametersT, ResultT>>
        get() = mutableDialogState.asSharedFlow()

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
     * @param resultChannel is a [SendChannel] where user input should be sent.
     */
    class DialogShownState<ParametersT, ResultT>(
        val parameters: ParametersT,
        val resultChannel: SendChannel<ResultT>
    ): DialogState<ParametersT, ResultT>()

    suspend fun displayPrompt(
        parameters: ParametersT,
        lingerDuration: Duration = 0.seconds
    ): ResultT {
        if (mutableDialogState.subscriptionCount.value == 0) {
            throw PromptUiNotAvailableException()
        }
        // TODO: strictly speaking we need to handle the case when the dialog is already shown.
        // Perhaps we should just add a mutex to ensure that this never happens as it is not
        // something that should occur in a well-written app anyway...
        val resultChannel = Channel<ResultT>(Channel.RENDEZVOUS)
        mutableDialogState.emit(DialogShownState(parameters, resultChannel))
        var effectiveLingerDuration = lingerDuration
        return try {
            resultChannel.receive()
        } catch (err: PromptDismissedException) {
            // User dismissed, don't linger
            effectiveLingerDuration = 0.seconds
            throw err
        } catch (err: CancellationException) {
            // Coroutine cancelled, don't linger
            effectiveLingerDuration = 0.seconds
            throw err
        } finally {
            if (effectiveLingerDuration.isPositive() && coroutineContext.isActive) {
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        delay(effectiveLingerDuration)
                    } finally {
                        withContext(NonCancellable) {
                            mutableDialogState.emit(NoDialogState(false))
                        }
                    }
                }
            } else {
                // Using NonCancellable is important to change the dialog state to dismissed
                // when this coroutine is in cancelling state.
                withContext(NonCancellable) {
                    mutableDialogState.emit(NoDialogState(false))
                }
            }
        }
    }
}