package org.multipaz.prompt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Base model object for prompts.
 *
 * Prompt is a UI dialog (typically a modal bottom sheet dialog) that asynchronous code can
 * pop up merely by calling a specialized function like [requestPassphrase] (more generally
 * [PromptDialogModel.displayPrompt] on a dialog model obtained through [getDialogModel]).
 * Such function will pop up the dialog, and then suspend until user enters the input, performs
 * the required action or dismisses the dialog. User input is then returned to the caller
 * (or [PromptDismissedException] is thrown if the dialog is dismissed).
 *
 * [PromptModel] must exist in the current [CoroutineContext] for prompt functions to work.
 *  - An instance of [PromptModel] can be added to the context using [withContext].
 *  - There is a predefined coroutine scope [promptModelScope] that is bound to the context that
 *    contains this model (it can be used similar to `ViewModel.viewModelScope` on Android).
 *  - In Composable environment [PromptModel] can be used with `rememberCoroutineScope` like
 *    this:
 *    ```
 *    val myScope = rememberCoroutineScope { promptModel }
 *    ```
 *
 * [PromptModel] is actually a holder object for individual dialog models [PromptDialogModel].
 * More dialog types can be registered by creating and registering [PromptDialogModel] classes in
 * the platform [PromptModel]. Each dialog must be bound to its UI independently (see
 * `org.multipaz.compose.prompt.PromptDialogs` in `multipaz-compose` for an example), in
 * different UI scenarios a different set of dialogs may be supported.
 *
 * This class is abstract, as each platform should generally implement its own variant, in
 * particular by providing an appropriate [promptModelScope].
 */
abstract class PromptModel protected constructor(
    builder: Builder
) : CoroutineContext.Element {
    object Key: CoroutineContext.Key<PromptModel>

    override val key: CoroutineContext.Key<PromptModel>
        get() = Key

    /**
     * This method is called when there is no UI that is bound to a particular [PromptDialogModel].
     *
     * This method can attempt to launch UI, e.g. start an `Activity` on Android.
     */
    open suspend fun launchUi(dialogModel: PromptDialogModel<*,*>) {}

    private val promptDialogModels: Map<PromptDialogModel.DialogType<*>, PromptDialogModel<*,*>> =
        builder.promptDialogModels.toMap()

    abstract val promptModelScope: CoroutineScope

    val toHumanReadable: ConvertToHumanReadableFn = builder.toHumanReadable

    init {
        promptDialogModels.values.forEach {
            check(it.owningPromptModel == null)
            it.owningPromptModel = this
        }
    }

    /**
     * Extracts [PromptDialogModel] of the required type from this [PromptModel].
     *
     * @param dialogType required type of the dialog model
     * @throws IllegalStateException if [PromptModel] does not have dialog model of the required
     *    type registered
     */
    fun<PromptDialogModelT: PromptDialogModel<*,*>> getDialogModel(
        dialogType: PromptDialogModel.DialogType<PromptDialogModelT>
    ): PromptDialogModelT {
        val dialogModel = promptDialogModels[dialogType]
            ?: throw IllegalStateException("Unknown dialog model $dialogType")
        check(dialogModel.dialogType === dialogType)
        @Suppress("UNCHECKED_CAST")
        return dialogModel as PromptDialogModelT
    }

    /**
     * Builder for [PromptModel].
     *
     *
     */
    abstract class Builder(
        val toHumanReadable: ConvertToHumanReadableFn
    ) {
        internal val promptDialogModels = mutableMapOf<PromptDialogModel.DialogType<*>, PromptDialogModel<*,*>>()

        fun<PromptDialogModelT: PromptDialogModel<*,*>> addPromptDialogModel(
            dialogModel: PromptDialogModelT
        ) {
            val dialogType = dialogModel.dialogType
            check(!promptDialogModels.containsKey(dialogType))
            promptDialogModels[dialogType] = dialogModel
        }

        open fun addCommonDialogs() {
            addPromptDialogModel(PassphrasePromptDialogModel())
        }

        abstract fun build(): PromptModel
    }

    companion object {
        /**
         * Return injected [PromptModel] from the current coroutine scope.
         */
        suspend fun get(): PromptModel {
            return currentCoroutineContext()[Key] ?: throw PromptModelNotAvailableException()
        }
    }
}
