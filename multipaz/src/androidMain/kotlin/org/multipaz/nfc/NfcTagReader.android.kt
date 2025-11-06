package org.multipaz.nfc

import android.content.pm.PackageManager
import org.multipaz.context.applicationContext
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.NfcDialogParameters
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
        val promptModel = AndroidPromptModel.get(coroutineContext)
        val result = promptModel.scanNfcPromptModel.displayPrompt(
            NfcDialogParameters(message, tagInteractionFunc, options, context)
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
