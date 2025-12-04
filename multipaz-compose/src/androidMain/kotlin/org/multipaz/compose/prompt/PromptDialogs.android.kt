package org.multipaz.compose.prompt

import androidx.compose.runtime.Composable
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.BiometricPromptDialogModel
import org.multipaz.prompt.PassphrasePromptDialogModel
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.ScanNfcPromptDialogModel

@Composable
actual fun PromptDialogs(promptModel: PromptModel) {
    val model = promptModel as AndroidPromptModel
    ScanNfcTagPromptDialog(model.getDialogModel(ScanNfcPromptDialogModel.DialogType))
    BiometricPromptDialog(model.getDialogModel(BiometricPromptDialogModel.DialogType))
    PassphrasePromptDialog(model.getDialogModel(PassphrasePromptDialogModel.DialogType))
}