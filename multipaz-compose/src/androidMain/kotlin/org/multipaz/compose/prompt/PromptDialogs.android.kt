package org.multipaz.compose.prompt

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.BiometricPromptDialogModel
import org.multipaz.prompt.ConsentPromptDialogModel
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PassphrasePromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.ScanNfcPromptDialogModel

@Composable
actual fun PromptDialogs(
    promptModel: PromptModel,
    imageLoader: ImageLoader?,
    maxHeight: Dp?,
    excludeTypes: List<PromptDialogModel.DialogType<*>>,
    toHumanReadable: ConvertToHumanReadableFn
) {
    val model = promptModel as AndroidPromptModel

    if (!excludeTypes.contains(ScanNfcPromptDialogModel.DialogType)) {
        ScanNfcTagPromptDialog(model.getDialogModel(ScanNfcPromptDialogModel.DialogType))
    }
    if (!excludeTypes.contains(BiometricPromptDialogModel.DialogType)) {
        BiometricPromptDialog(
            model = model.getDialogModel(BiometricPromptDialogModel.DialogType),
            toHumanReadable = { reason -> toHumanReadable(reason, null) }
        )
    }
    if (!excludeTypes.contains(PassphrasePromptDialogModel.DialogType)) {
        PassphrasePromptDialog(
            model = model.getDialogModel(PassphrasePromptDialogModel.DialogType),
            toHumanReadable = toHumanReadable
        )
    }
    if (!excludeTypes.contains(ConsentPromptDialogModel.DialogType)) {
        ConsentPromptDialog(
            model = model.getDialogModel(ConsentPromptDialogModel.DialogType),
            imageLoader = imageLoader,
            maxHeight = maxHeight
        )
    }
    if (!excludeTypes.contains(FaceMatcherPromptDialogModel.DialogType)) {
        FaceMatcherPromptDialog(
            model = model.getDialogModel(FaceMatcherPromptDialogModel.DialogType),
            toHumanReadable = toHumanReadable
        )
    }
}