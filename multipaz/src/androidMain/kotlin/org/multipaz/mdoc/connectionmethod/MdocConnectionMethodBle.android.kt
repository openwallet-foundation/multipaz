package org.multipaz.mdoc.connectionmethod

import android.os.Build
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MdocConnectionMethodBle"

actual suspend fun MdocConnectionMethodBle.Companion.isChannelSoundingAvailable(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        val rangingManager = applicationContext.getSystemService(RangingManager::class.java)
            ?: return false
        return withTimeoutOrNull(500.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : RangingManager.RangingCapabilitiesCallback {
                    override fun onRangingCapabilities(capabilities: RangingCapabilities) {
                        try {
                            rangingManager.unregisterCapabilitiesCallback(this)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Error unregistering capabilities callback", e)
                        }
                        val availability = capabilities.technologyAvailability?.get(RangingManager.BLE_CS)
                        if (continuation.isActive) {
                            continuation.resume(availability == RangingCapabilities.ENABLED)
                        }
                    }
                }
                try {
                    rangingManager.registerCapabilitiesCallback({ it.run() }, callback)
                    continuation.invokeOnCancellation {
                        try {
                            rangingManager.unregisterCapabilitiesCallback(callback)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Error unregistering capabilities callback on cancellation", e)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error registering capabilities callback", e)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: false
    }
    return false
}
