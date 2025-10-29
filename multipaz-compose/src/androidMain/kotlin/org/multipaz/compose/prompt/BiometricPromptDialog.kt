package org.multipaz.compose.prompt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.multipaz.context.getActivity
import org.multipaz.prompt.BiometricPromptState
import org.multipaz.prompt.SinglePromptModel
import androidx.lifecycle.compose.currentStateAsState

/**
 * Displays biometric prompt dialog in Composable UI environment.
 */
@Composable
internal fun BiometricPromptDialog(model: SinglePromptModel<BiometricPromptState, Boolean>) {
    val activity = LocalContext.current.getActivity() as FragmentActivity
    val dialogState = model.dialogState.collectAsState(SinglePromptModel.NoDialogState())
    val dialogStateValue = dialogState.value
    if (!activity.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)) {
        // Only currently-running activity should show biometric prompt
        return
    }
    LaunchedEffect(dialogStateValue) {
        when (dialogStateValue) {
            is SinglePromptModel.DialogShownState -> {
                val dialogParameters = dialogStateValue.parameters
                try {
                    val result = org.multipaz.compose.biometrics.showBiometricPrompt(
                        activity = activity,
                        cryptoObject = dialogParameters.cryptoObject,
                        title = dialogParameters.title,
                        subtitle = dialogParameters.subtitle,
                        userAuthenticationTypes = dialogParameters.userAuthenticationTypes,
                        requireConfirmation = dialogParameters.requireConfirmation
                    )
                    dialogStateValue.resultChannel.send(result)
                } catch (err: Throwable) {
                    dialogStateValue.resultChannel.send(false)
                }
            }
            else -> {}
        }
    }
}