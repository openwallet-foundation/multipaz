package org.multipaz.nfc

import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.context.getActivity
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcTagReaderAndroid"

private class NfcReaderCallback<T>(
    private val reader: NfcTagReaderAndroidImpl,
    private val tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
    private val updateMessage: (String) -> Unit,
    private val resetMessage: () -> Unit,
    private val continuation: CancellableContinuation<T>,
    private val context: CoroutineContext
) : NfcAdapter.ReaderCallback {
    override fun onTagDiscovered(tag: Tag?) {
        if (!continuation.isActive) {
            return
        }
        if (tag == null) {
            Logger.e(TAG, "onTagDiscovered called with null tag")
            return
        }
        for (tech in tag.techList) {
            if (tech == IsoDep::class.java.name) {
                val isoDep = IsoDep.get(tag)!!
                try {
                    isoDep.connect()
                    isoDep.timeout = 20.seconds.inWholeMilliseconds.toInt()
                } catch (e: IOException) {
                    Logger.w(TAG, "Failed to connect to IsoDep tag", e)
                    return
                }
                reader.onTagConnected { isoDep.isConnected }
                try {
                    // Note: onTagDiscovered() is called in a dedicated thread and we're not supposed
                    // to return until we're done interrogating the tag.
                    val ret = runBlocking {
                        Logger.i(TAG, "maxTransceiveLength: ${isoDep.maxTransceiveLength}")
                        val isoTag = NfcIsoTagAndroid(
                            tag = isoDep,
                            context = context,
                            updateMessage = updateMessage
                        )
                        tagInteractionFunc(isoTag)
                    }
                    Logger.i(TAG, "Tag processed")
                    if (ret == null) {
                        // Keep on listening
                        resetMessage()
                    } else {
                        if (continuation.isActive) {
                            continuation.resume(ret)
                        }
                    }
                } catch (e: NfcTagLostException) {
                    // This is to properly handle emulated tags - such as on Android - which may be showing
                    // disambiguation UI if multiple applications have registered for the same AID.
                    resetMessage()
                    Logger.w(TAG, "Tag lost", e)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Error in interaction func", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
                return
            }
        }
    }
}

internal class NfcTagReaderAndroidImpl(
    private val disableReaderModeDelay: Duration = 5.seconds,
    private val pollInterval: Duration = 100.milliseconds,
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : NfcTagReader {
    private val lock = Any()
    private var disableReaderModeJob: Job? = null
    private var disableReaderModeAction: (() -> Unit)? = null
    private var isTagConnected: (() -> Boolean)? = null

    override val external: Boolean
        get() = false

    override val dialogAlwaysShown: Boolean
        get() = false

    internal fun onTagConnected(checkConnected: () -> Boolean) {
        synchronized(lock) {
            isTagConnected = checkConnected
        }
    }

    override suspend fun waitUntilRemoved() {
        val checkConnected = synchronized(lock) {
            val current = isTagConnected ?: return
            disableReaderModeJob?.cancel()
            disableReaderModeJob = null
            current
        }
        try {
            while (
                withContext(Dispatchers.IO) {
                    try {
                        checkConnected()
                    } catch (e: SecurityException) {
                        Logger.w(TAG, "Tag is out of date while waiting for removal", e)
                        false
                    }
                }
            ) {
                delay(pollInterval)
            }
        } finally {
            var toDisable: (() -> Unit)? = null
            synchronized(lock) {
                if (isTagConnected === checkConnected) {
                    isTagConnected = null
                    toDisable = disableReaderModeAction
                    disableReaderModeAction = null
                }
            }
            toDisable?.invoke()
        }
    }

    internal suspend fun <T : Any> runScanSession(
        message: String?,
        enableReaderMode: (
            updateMessage: (String) -> Unit,
            resetMessage: () -> Unit,
            continuation: CancellableContinuation<T>
        ) -> Unit,
        disableReaderMode: () -> Unit,
    ): T {
        synchronized(lock) {
            disableReaderModeJob?.cancel()
            disableReaderModeJob = null
            disableReaderModeAction = null
            isTagConnected = null
        }
        var scanCancelled = false
        try {
            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                suspendCancellableCoroutine { continuation ->
                    enableReaderMode(updateMessage, resetMessage, continuation)
                }
            }
        } catch (e: CancellationException) {
            scanCancelled = true
            throw e
        } catch (e: PromptDismissedException) {
            scanCancelled = true
            throw e
        } finally {
            var disableImmediately = false
            synchronized(lock) {
                if (scanCancelled || isTagConnected == null) {
                    isTagConnected = null
                    disableReaderModeAction = null
                    disableImmediately = true
                } else {
                    disableReaderModeAction = disableReaderMode
                    Logger.i(TAG, "NFC: waiting to disable reader mode")
                    lateinit var job: Job
                    job = backgroundScope.launch {
                        delay(disableReaderModeDelay)
                        var toDisable: (() -> Unit)? = null
                        synchronized(lock) {
                            if (disableReaderModeJob === job) {
                                disableReaderModeJob = null
                                isTagConnected = null
                                toDisable = disableReaderModeAction
                                disableReaderModeAction = null
                            }
                        }
                        toDisable?.invoke()
                    }
                    disableReaderModeJob = job
                }
            }
            if (disableImmediately) {
                disableReaderMode()
            }
        }
    }

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val activity = AndroidUiContext.current().getActivity()
            ?: throw IllegalStateException("Context in AndroidUiContext is not an Activity")
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: throw IllegalStateException("NFC is not supported on this device")

        return runScanSession(
            message = message,
            enableReaderMode = { updateMessage, resetMessage, continuation ->
                val nfcCallback = NfcReaderCallback(
                    reader = this,
                    tagInteractionFunc = tagInteractionFunc,
                    updateMessage = updateMessage,
                    resetMessage = resetMessage,
                    continuation = continuation,
                    context = context
                )
                val extras = options.pollingFrameData?.let {
                    val bundle = Bundle()
                    bundle.putByteArray("android.nfc.extra.READER_TECH_A_POLLING_LOOP_ANNOTATION", it.toByteArray())
                    bundle
                }
                adapter.enableReaderMode(
                    activity,
                    nfcCallback,
                    NfcAdapter.FLAG_READER_NFC_A + NfcAdapter.FLAG_READER_NFC_B
                            + NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK + NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    extras
                )
            },
            disableReaderMode = {
                Logger.i(TAG, "NFC: reader mode disabled")
                adapter.disableReaderMode(activity)
            }
        )
    }
}

private val NfcTagReaderAndroid = NfcTagReaderAndroidImpl()

internal actual fun nfcGetPlatformReaders(): List<NfcTagReader> {
    return if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
        listOf(NfcTagReaderAndroid)
    } else {
        emptyList()
    }
}
