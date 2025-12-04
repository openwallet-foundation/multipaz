package org.multipaz.compose.prompt

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import org.multipaz.prompt.PromptDialogModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.multipaz.compose.passphrase.PassphrasePromptBottomSheet
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PassphrasePromptDialogModel
import org.multipaz.prompt.PromptDismissedException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphrasePromptDialog(model: PromptDialogModel<PassphrasePromptDialogModel.PassphraseRequest, String>) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val showKeyboard = MutableStateFlow<Boolean>(false)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            showKeyboard.value = true
            true
        }
    )
    val dialogStateValue = dialogState.value
    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val dialogParameters = dialogStateValue.parameters
        PassphrasePromptBottomSheet(
            sheetState = sheetState,
            title = dialogParameters.title,
            subtitle = dialogParameters.subtitle,
            passphraseConstraints = dialogParameters.passphraseConstraints,
            showKeyboard = showKeyboard.asStateFlow(),
            onPassphraseEntered = { enteredPassphrase ->
                val evaluator = dialogParameters.passphraseEvaluator
                if (evaluator != null) {
                    val matchResult = evaluator.invoke(enteredPassphrase)
                    if (matchResult != PassphraseEvaluation.OK) {
                        return@PassphrasePromptBottomSheet matchResult
                    }
                }
                dialogStateValue.resultChannel.send(enteredPassphrase)
                PassphraseEvaluation.OK
            },
            onDismissed = {
                coroutineScope.launch {
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            },
        )
    }
}