package org.multipaz.testapp

import android.content.ComponentName
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.testapp.NfcObserveModeHelper.inhibitObserveModeForTransaction
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant


class NfcObserveModeHelperService : HostApduService() {
    companion object {
        private const val TAG = "NfcObserveModeHelperService"
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "onDestroy")
    }

    override fun onCreate() {
        Logger.i(TAG, "onCreate")
        super.onCreate()
        initializeApplication(applicationContext)

        NfcObserveModeHelper.updateObserveMode()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun processPollingFrames(frames: List<PollingFrame>) {
        Logger.i(TAG, "processPollingFrames (${frames.size} frames)")
        NfcObserveModeHelper.onPollingFramesReceived(frames)
        var foundIdentityReader = false
        for (frame in frames) {
            if (frame.data.toHex().startsWith("6a028103")) {
                foundIdentityReader = true
            }
        }
        if (foundIdentityReader && !NfcObserveModeHelper.isForceEnabledAndCapturingAllFrames) {
            Logger.i(TAG, "Detected identity reader and in observe mode: " +
                    "inhibiting observe to allow transaction to go through"
            )
            // TODO: auth the user if needed
            inhibitObserveModeForTransaction()
        }
    }

    override fun onDeactivated(p0: Int) {
    }

    override fun processCommandApdu(p0: ByteArray?, p1: Bundle?): ByteArray? {
        return null
    }
}

// Need to implement this so it works even when the application is not initialized.. this
// is because the OS might create NfcObserveModeHelperService without the app running.
//
actual object NfcObserveModeHelper {
    private const val TAG = "NfcObserveModeHelper"
    private const val MAX_RECORDED_FRAMES = 500

    private val sharedPreferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("NfcObserveModeHelper", 0)
    }

    private var localEnabledValue: Boolean? = null

    actual var isEnabled: Boolean
        get() {
            localEnabledValue?.let { return it }
            localEnabledValue = sharedPreferences.getBoolean("observeModeEnabled", false)
            return localEnabledValue!!
        }
        set(value) {
            sharedPreferences.edit(commit = true) {
                putBoolean("observeModeEnabled", value)
                localEnabledValue = value
            }
            updateObserveMode()
        }

    private var forceEnabledAndCapturingAllFramesValue = false

    actual var isForceEnabledAndCapturingAllFrames: Boolean
        get() = forceEnabledAndCapturingAllFramesValue
        set(value) {
            if (forceEnabledAndCapturingAllFramesValue != value) {
                forceEnabledAndCapturingAllFramesValue = value
                updatePollingFiltersForMode()
                updateObserveMode()
            }
        }

    private val _state = MutableStateFlow(NfcObserveModeState())
    actual val state: StateFlow<NfcObserveModeState> = _state.asStateFlow()

    private var nextFrameId = 1L
    private val _pollingFrames = MutableStateFlow<List<NfcPollingFrame>>(emptyList())
    actual val pollingFrames: StateFlow<List<NfcPollingFrame>> = _pollingFrames.asStateFlow()

    actual fun clearPollingFrames() {
        _pollingFrames.value = emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    internal fun onPollingFramesReceived(frames: List<PollingFrame>) {
        val now = Clock.System.now()
        val converted = frames.map { frame ->
            val type = when (frame.type) {
                PollingFrame.POLLING_LOOP_TYPE_A -> NfcPollingFrameType.A
                PollingFrame.POLLING_LOOP_TYPE_B -> NfcPollingFrameType.B
                PollingFrame.POLLING_LOOP_TYPE_F -> NfcPollingFrameType.F
                PollingFrame.POLLING_LOOP_TYPE_ON -> NfcPollingFrameType.REMOTE_FIELD_ON
                PollingFrame.POLLING_LOOP_TYPE_OFF -> NfcPollingFrameType.REMOTE_FIELD_OFF
                else -> NfcPollingFrameType.UNKNOWN
            }
            NfcPollingFrame(
                id = nextFrameId++,
                type = type,
                data = ByteString(frame.data),
                vendorSpecificGain = frame.vendorSpecificGain,
                timestampMicros = frame.timestamp,
                triggeredAutoTransact = frame.triggeredAutoTransact,
                observedAt = now,
            )
        }
        for (item in converted) {
            Logger.i(
                TAG,
                "Observed frame #${item.id}: type=${item.type} data=${item.data.toHex()} " +
                    "gain=${item.vendorSpecificGain} ts=${item.timestampMicros}us " +
                    "autoTransact=${item.triggeredAutoTransact}"
            )
        }
        _pollingFrames.update { existing ->
            val combined = existing + converted
            if (combined.size > MAX_RECORDED_FRAMES) {
                combined.takeLast(MAX_RECORDED_FRAMES)
            } else {
                combined
            }
        }
    }

    private var observeModeExplicitlyInhibited = false
    private var inhbitForTransactionUntil: Instant? = null

    // Inhibits observe mode for 5 seconds to allow a transaction to go through
    actual fun inhibitObserveModeForTransaction() {
        inhbitForTransactionUntil = Clock.System.now() + 5.seconds
        updateObserveMode()
    }

    private var pollJob: Job? = null

    internal fun updateObserveMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            _state.value = NfcObserveModeState(
                isSupported = false,
                isEnabledInSettings = isEnabled,
                isForceEnabled = forceEnabledAndCapturingAllFramesValue,
                isEnabledOnAdapter = false,
                isExplicitlyInhibited = observeModeExplicitlyInhibited,
                isInhibitedForTransaction = false,
            )
            return
        }

        ensurePollingFiltersRegistered()

        val effectivelyEnabled = isEnabled || forceEnabledAndCapturingAllFramesValue
        if (!effectivelyEnabled) {
            if (pollJob != null) {
                pollJob?.cancel()
                pollJob = null
            }
        } else {
            if (pollJob == null) {
                pollJob = CoroutineScope(Dispatchers.IO).launch {
                    while (true) {
                        delay(1.seconds)
                        updateObserveMode()
                    }
                }
            }
        }

        val adapter = NfcAdapter.getDefaultAdapter(applicationContext)
        if (adapter != null) {
            val isSupported = adapter.isObserveModeSupported
            var isInhibitedForTx = false
            val observeModeShouldBeEnabled = if (forceEnabledAndCapturingAllFramesValue) {
                true
            } else if (isEnabled) {
                if (observeModeExplicitlyInhibited) {
                    false
                } else {
                    inhbitForTransactionUntil?.let {
                        val now = Clock.System.now()
                        if (now < it) {
                            isInhibitedForTx = true
                            false
                        } else {
                            inhbitForTransactionUntil = null
                            true
                        }
                    } ?: true
                }
            } else {
                false
            }
            if (isSupported) {
                val isObserveModeEnabledOnAdapter = adapter.isObserveModeEnabled
                if (isObserveModeEnabledOnAdapter != observeModeShouldBeEnabled) {
                    Logger.i(
                        TAG,
                        "isObserveModeEnabled=$isObserveModeEnabledOnAdapter changing to $observeModeShouldBeEnabled"
                    )
                    adapter.isObserveModeEnabled = observeModeShouldBeEnabled
                }
            }
            _state.value = NfcObserveModeState(
                isSupported = isSupported,
                isEnabledInSettings = isEnabled,
                isForceEnabled = forceEnabledAndCapturingAllFramesValue,
                isEnabledOnAdapter = if (isSupported) adapter.isObserveModeEnabled else false,
                isExplicitlyInhibited = observeModeExplicitlyInhibited,
                isInhibitedForTransaction = isInhibitedForTx,
            )
        } else {
            _state.value = NfcObserveModeState(
                isSupported = false,
                isEnabledInSettings = isEnabled,
                isForceEnabled = forceEnabledAndCapturingAllFramesValue,
                isEnabledOnAdapter = false,
                isExplicitlyInhibited = observeModeExplicitlyInhibited,
                isInhibitedForTransaction = false,
            )
        }
    }

    actual fun inhibitObserveMode() {
        Logger.i(TAG, "inhibitObserveMode() called")
        if (observeModeExplicitlyInhibited) {
            Logger.w(TAG, "inhibitObserveMode() called but observeModeExplicitlyInhibited is already true")
        }
        observeModeExplicitlyInhibited = true
        updateObserveMode()
    }

    actual fun uninhibitObserveMode() {
        Logger.i(TAG, "uninhibitObserveMode() called")
        if (!observeModeExplicitlyInhibited) {
            Logger.w(TAG, "uninhibitObserveMode() called but observeModeExplicitlyInhibited is not true")
        }
        observeModeExplicitlyInhibited = false
        updateObserveMode()
    }

    private var pollingFiltersRegistered = false
    private var wildcardFilterRegistered = false

    private fun updatePollingFiltersForMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(applicationContext) ?: return
        if (!adapter.isObserveModeSupported) {
            return
        }
        val componentName = ComponentName(applicationContext, NfcObserveModeHelperService::class.java)
        val cardEmulation = CardEmulation.getInstance(adapter)
        if (forceEnabledAndCapturingAllFramesValue && !wildcardFilterRegistered) {
            try {
                cardEmulation.registerPollingLoopPatternFilterForService(
                    componentName,
                    ".*",
                    false
                )
                wildcardFilterRegistered = true
                Logger.i(TAG, "Registered wildcard polling loop pattern filter '.*'")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to register wildcard polling loop pattern filter", e)
            }
        } else if (!forceEnabledAndCapturingAllFramesValue && wildcardFilterRegistered) {
            try {
                cardEmulation.removePollingLoopPatternFilterForService(
                    componentName,
                    ".*"
                )
                wildcardFilterRegistered = false
                Logger.i(TAG, "Removed wildcard polling loop pattern filter '.*'")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to remove wildcard polling loop pattern filter", e)
            }
        }
    }

    private fun ensurePollingFiltersRegistered() {
        if (pollingFiltersRegistered) {
            return
        }

        val adapter = NfcAdapter.getDefaultAdapter(applicationContext)
        if (adapter == null) {
            Logger.w(TAG, "No NFC adapter available")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Logger.i(
                TAG, "Observe mode not supported by Android version ${Build.VERSION.SDK_INT}, " +
                    "requires  ${Build.VERSION_CODES.BAKLAVA} or later")
            return
        }

        if (!adapter.isObserveModeSupported) {
            Logger.i(TAG, "Observe mode not supported by adapter")
            return
        }

        val componentName = ComponentName(applicationContext, NfcObserveModeHelperService::class.java)
        val cardEmulation = CardEmulation.getInstance(adapter)

        cardEmulation.registerPollingLoopPatternFilterForService(
            componentName,
            "6a028103.*",
            false
        )

        if (forceEnabledAndCapturingAllFramesValue && !wildcardFilterRegistered) {
            try {
                cardEmulation.registerPollingLoopPatternFilterForService(
                    componentName,
                    ".*",
                    false
                )
                wildcardFilterRegistered = true
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to register wildcard polling loop pattern filter", e)
            }
        }

        cardEmulation.registerNfcEventCallback(
            applicationContext.mainExecutor,
            object : CardEmulation.NfcEventCallback {
                override fun onObserveModeStateChanged(isEnabled: Boolean) {
                    Logger.i(TAG, "onObserveModeStateChanged: $isEnabled")
                    _state.update { it.copy(isEnabledOnAdapter = isEnabled) }
                }
            }
        )

        Logger.i(TAG, "Polling filters registered")
        pollingFiltersRegistered = true
    }
}

@Composable
actual fun rememberInhibitNfcObserveMode() {
    val recomposeCounter = remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            recomposeCounter.intValue += 1
        }
        NfcObserveModeHelper.inhibitObserveMode()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            NfcObserveModeHelper.uninhibitObserveMode()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

