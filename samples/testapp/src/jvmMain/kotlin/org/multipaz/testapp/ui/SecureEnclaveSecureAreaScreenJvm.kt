package org.multipaz.testapp.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.multipaz.prompt.PromptModel

@Composable
actual fun SecureEnclaveSecureAreaScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit
) {
    Text(text = "SecureEnclaveSecureArea is only available on iOS")
}
