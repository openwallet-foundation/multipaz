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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import androidx.core.content.edit
import org.multipaz.testapp.NfcObserveModeHelper.inhibitObserveModeForTransaction


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
        Logger.i(TAG, "processPollingFrames")
        var foundIdentityReader = false
        for (frame in frames) {
            if (frame.data.toHex().startsWith("6a028103")) {
                foundIdentityReader = true
            }
        }
        if (foundIdentityReader) {
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
            return
        }

        ensurePollingFiltersRegistered()

        if (!isEnabled) {
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
        val isObserveModeEnabledOnAdapter = adapter.isObserveModeEnabled
        val observeModeShouldBeEnabled = if (isEnabled) {
            if (observeModeExplicitlyInhibited) {
                false
            } else {
                inhbitForTransactionUntil?.let {
                    val now = Clock.System.now()
                    if (now < it) {
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
        if (isObserveModeEnabledOnAdapter != observeModeShouldBeEnabled) {
            Logger.i(TAG, "isObserveModeEnabled=$isObserveModeEnabledOnAdapter changing to $observeModeShouldBeEnabled")
            adapter.isObserveModeEnabled = observeModeShouldBeEnabled
        }
        //Logger.i(TAG, "observe mode enabled: ${adapter.isObserveModeEnabled}")
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

        cardEmulation.registerNfcEventCallback(
            applicationContext.mainExecutor,
            object : CardEmulation.NfcEventCallback {
                override fun onObserveModeStateChanged(isEnabled: Boolean) {
                    Logger.i(TAG, "onObserveModeStateChanged: $isEnabled")
                }
            }
        )

        Logger.i(TAG, "Polling filters registered")
        pollingFiltersRegistered = true
    }
}