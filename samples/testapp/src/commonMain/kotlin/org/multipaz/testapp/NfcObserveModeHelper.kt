package org.multipaz.testapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Simple abstraction for managing Observe Mode for Test App.
 *
 * At some point this could be cleaned up and moved to the Multipaz library.
 */
expect object NfcObserveModeHelper {

    /**
     * Whether observe mode is enabled.
     */
    var isEnabled: Boolean

    /**
     * When this is called observe mode is disabled for 5 seconds to allow a transaction to go through.
     */
    fun inhibitObserveModeForTransaction()

    /**
     * Inhibits observe mode.
     *
     * This inhibits observe mode until [uninhibitObserveMode] is called.
     *
     * Generally this should be called when on the screen where the user has selected a document. The
     * [rememberInhibitNfcObserveMode] composable can be used to do this automatically.
     */
    fun inhibitObserveMode()

    /**
     * Uninhibits observe mode.
     *
     * See [inhibitObserveMode] for how to use this.
     */
    fun uninhibitObserveMode()
}

/**
 * Composable for calling [NfcObserveModeHelper.inhibitObserveMode] and
 * [NfcObserveModeHelper.uninhibitObserveMode] when entering respectively
 * leaving a composable.
 */
@Composable
fun rememberInhibitNfcObserveMode() {
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
