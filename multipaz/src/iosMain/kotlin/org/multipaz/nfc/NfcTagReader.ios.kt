package org.multipaz.nfc

import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import org.multipaz.util.toKotlinError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreNFC.NFCISO7816TagProtocol
import platform.CoreNFC.NFCPollingISO14443
import platform.CoreNFC.NFCTagProtocol
import platform.CoreNFC.NFCTagReaderSession
import platform.CoreNFC.NFCTagReaderSessionDelegateProtocol
import platform.Foundation.NSError
import platform.CoreNFC.NFCErrorDomain
import platform.CoreNFC.NFCReaderSessionInvalidationErrorUserCanceled
import platform.darwin.NSObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

private const val TAG = "NfcReader"

private class IosTagReader<T>(
    context: CoroutineContext
) {
    val session: NFCTagReaderSession

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tagReaderSessionDelegate = object : NSObject(), NFCTagReaderSessionDelegateProtocol {
        override fun tagReaderSession(
            session: NFCTagReaderSession,
            didInvalidateWithError: NSError
        ) {
            if (didInvalidateWithError.domain == NFCErrorDomain &&
                didInvalidateWithError.code == NFCReaderSessionInvalidationErrorUserCanceled) {
                continuation?.resumeWithException(PromptDismissedException())
                continuation = null
            } else {
                continuation?.resumeWithException(didInvalidateWithError.toKotlinError())
                continuation = null
            }
        }

        override fun tagReaderSessionDidBecomeActive(
            session: NFCTagReaderSession
        ) {
        }

        override fun tagReaderSession(
            session: NFCTagReaderSession,
            didDetectTags: List<*>
        ) {
            // Currently we only consider the first tag. We might need to look at all tags.
            val tag = didDetectTags[0] as NFCTagProtocol
            session.connectToTag(tag) { error ->
                if (error != null) {
                    Logger.e(TAG, "Connection failed", error.toKotlinError())
                } else {
                    val isoTag = NfcIsoTagIos(
                        tag = tag as NFCISO7816TagProtocol,
                        session = session
                    )
                    CoroutineScope(context).launch {
                        try {
                            val ret = tagInteractionFunc(isoTag)
                            if (ret != null) {
                                continuation?.resume(Pair(ret, isoTag), null)
                                continuation = null
                            } else {
                                session.restartPolling()
                            }
                        } catch (e: Throwable) {
                            continuation?.resumeWithException(e)
                            continuation = null
                        }
                    }
                }
            }
        }
    }

    init {
        session = NFCTagReaderSession(
            pollingOption = NFCPollingISO14443,
            delegate = tagReaderSessionDelegate,
            queue = null,
        )
    }

    private var continuation: CancellableContinuation<Pair<T, NfcIsoTagIos>>? = null

    private lateinit var tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?

    suspend fun beginSession(
        alertMessage: String,
        tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?
    ): T {
        check(NFCTagReaderSession.readingAvailable) { "The device doesn't support NFC tag reading" }
        try {
            val (ret, tag) = suspendCancellableCoroutine { continuation ->
                this.continuation = continuation
                this.tagInteractionFunc = tagInteractionFunc
                session.setAlertMessage(alertMessage)
                session.beginSession()
            }
            if (tag.closeCalled) {
                session.invalidateSession()
            } else {
                Logger.i(TAG, "NfcIsoTag.close() not yet called, keeping session alive until then")
                tag.invalidateSessionOnClose = true
            }
            return ret
        } catch (e: CancellationException) {
            session.invalidateSessionWithErrorMessage("Dialog was canceled")
            throw e
        } catch (e: Throwable) {
            e.message?.let { session.invalidateSessionWithErrorMessage(it) } ?: session.invalidateSession()
            throw e
        }
    }
}


private object NfcTagReaderIos: NfcTagReader {
    override val external: Boolean
        get() = false

    override val dialogAlwaysShown: Boolean
        get() = true

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        require(message != null) { "Cannot not show the NFC tag scanning dialog on iOS" }
        val reader = IosTagReader<T>(context)
        return reader.beginSession(message, tagInteractionFunc)
    }
}

internal actual fun nfcGetPlatformReaders(): List<NfcTagReader> {
    return listOf(NfcTagReaderIos)
}
