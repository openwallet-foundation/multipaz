package org.multipaz.nfc

import android.content.pm.PackageManager
import org.multipaz.context.applicationContext
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.ScanNfcPromptDialogModel
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

private object NfcTagReaderAndroid: NfcTagReader {
    override val external: Boolean
        get() = false

    override val dialogAlwaysShown: Boolean
        get() = false

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val promptModel = AndroidPromptModel.get()
        val result = promptModel.getDialogModel(ScanNfcPromptDialogModel.DialogType).displayPrompt(
            parameters = ScanNfcPromptDialogModel.NfcDialogParameters(message, tagInteractionFunc, options, context),
            lingerDuration = if (message != null) 2.seconds else 0.seconds
        )
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

internal actual fun nfcGetPlatformReaders(): List<NfcTagReader> {
    return if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
        listOf(NfcTagReaderAndroid)
    } else {
        emptyList()
    }
}
