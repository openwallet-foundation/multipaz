package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.prompt.PromptModel

@Composable
actual fun AndroidKeystoreSecureAreaScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    onViewCertificate: (encodedCertificateData: String) -> Unit
) {
}
