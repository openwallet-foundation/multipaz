package org.multipaz.nci

import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import kotlin.coroutines.CoroutineContext

/**
 * [NfcTagReader] implementation backed by an [NciNfcController].
 */
class NfcTagReaderNci internal constructor(
    private val controller: NciNfcController,
    override val external: Boolean = false,
) : NfcTagReader {

    override val dialogAlwaysShown: Boolean
        get() = false

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        return controller.scan(
            message = message,
            tagInteractionFunc = tagInteractionFunc,
            options = options,
            context = context
        )
    }

    override suspend fun waitUntilRemoved() {
        controller.waitUntilRemoved()
    }
}
