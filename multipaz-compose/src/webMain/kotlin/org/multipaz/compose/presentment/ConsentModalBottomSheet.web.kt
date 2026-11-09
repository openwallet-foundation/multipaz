package org.multipaz.compose.presentment

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import org.multipaz.consent.ClassifySimpleConsentFn
import org.multipaz.document.Document
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.prompt.PlatformConsentOptions
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

internal actual fun shouldShowOnBiometricPrompt(
    platformConsentOptions: PlatformConsentOptions?,
): Boolean = false

@Composable
internal actual fun ConsentEmbeddedBiometricPrompt(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    classifySimpleConsentFn: ClassifySimpleConsentFn,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onShowDetails: () -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit,
) {
}
