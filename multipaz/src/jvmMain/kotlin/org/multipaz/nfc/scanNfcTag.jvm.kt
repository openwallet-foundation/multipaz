package org.multipaz.nfc

import kotlin.coroutines.CoroutineContext

actual val nfcTagScanningSupported = false

actual val nfcTagScanningSupportedWithoutDialog: Boolean = false

actual suspend fun<T: Any> scanNfcTag(
    message: String?,
    tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
    context: CoroutineContext
): T {
    throw NotImplementedError("scanNfcTag is not available for JVM")
}
