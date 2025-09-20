package org.multipaz.nfc

import android.content.pm.PackageManager
import org.multipaz.context.applicationContext
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.NfcDialogParameters
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext


actual val nfcTagScanningSupported by lazy {
    applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
}

actual val nfcTagScanningSupportedWithoutDialog: Boolean = nfcTagScanningSupported

actual suspend fun<T: Any> scanNfcTag(
    message: String?,
    tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
    context: CoroutineContext
): T {
    val promptModel = AndroidPromptModel.get(coroutineContext)
    val result = promptModel.scanNfcPromptModel.displayPrompt(
        NfcDialogParameters(message, tagInteractionFunc, context)
    )
    @Suppress("UNCHECKED_CAST")
    return result as T
}


