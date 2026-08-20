package org.multipaz.mdoc.transport

import android.os.Build
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger

private const val TAG = "BleChannelSounding"

internal actual suspend fun startBluetoothChannelSounding(
    peerBluetoothAddress: String,
    asInitiator: Boolean
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
        return
    }
    val rangingManager = applicationContext.getSystemService(RangingManager::class.java) ?: run {
        Logger.w(TAG, "RangingManager not available")
        return
    }

    var session: RangingSession? = null
    val roleStr = if (asInitiator) "Initiator" else "Responder"
    try {
        val csParams = BleCsRangingParams.Builder(peerBluetoothAddress).build()
        val rangingDevice = RangingDevice.Builder().build()
        val rawRangingDevice = RawRangingDevice.Builder()
            .setRangingDevice(rangingDevice)
            .setCsRangingParams(csParams)
            .build()
        val preference = if (asInitiator) {
            val rawConfig = RawInitiatorRangingConfig.Builder()
                .addRawRangingDevice(rawRangingDevice)
                .build()
            RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_INITIATOR,
                rawConfig
            ).build()
        } else {
            val rawConfig = RawResponderRangingConfig.Builder()
                .setRawRangingDevice(rawRangingDevice)
                .build()
            RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_RESPONDER,
                rawConfig
            ).build()
        }

        val callback = object : RangingSession.Callback {
            override fun onOpened() {
                Logger.i(TAG, "Ranging session opened ($roleStr) for $peerBluetoothAddress")
            }

            override fun onOpenFailed(reason: Int) {
                Logger.w(
                    TAG,
                    "Ranging session open failed ($roleStr, reason: ${reasonToString(reason)}) for $peerBluetoothAddress"
                )
            }

            override fun onStarted(device: RangingDevice, reason: Int) {
                Logger.i(TAG, "Ranging session started ($roleStr, reason: ${reasonToString(reason)})")
            }

            override fun onStopped(device: RangingDevice, reason: Int) {
                Logger.i(TAG, "Ranging session stopped ($roleStr, reason: ${reasonToString(reason)})")
            }

            override fun onClosed(reason: Int) {
                Logger.i(TAG, "Ranging session closed ($roleStr, reason: ${reasonToString(reason)})")
            }

            override fun onResults(device: RangingDevice, data: RangingData) {
                val distance = data.distance
                if (distance != null) {
                    println(
                        "Bluetooth Channel Sounding ($roleStr) distance: ${distance.measurement} m " +
                                "(confidence: ${distance.confidence})"
                    )
                    Logger.i(
                        TAG,
                        "Bluetooth Channel Sounding ($roleStr) distance: ${distance.measurement} m " +
                                "(confidence: ${distance.confidence})"
                    )
                }
            }
        }

        val rangingSession = rangingManager.createRangingSession({ it.run() }, callback) ?: run {
            Logger.w(TAG, "Failed to create RangingSession ($roleStr) for $peerBluetoothAddress")
            return
        }
        session = rangingSession
        rangingSession.start(preference)
        Logger.i(TAG, "Started Bluetooth Channel Sounding ranging ($roleStr) with $peerBluetoothAddress")

        awaitCancellation()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.w(TAG, "Error during Bluetooth Channel Sounding ($roleStr)", e)
    } finally {
        withContext(NonCancellable) {
            try {
                session?.stop()
                session?.close()
                Logger.i(TAG, "Stopped Bluetooth Channel Sounding session ($roleStr) for $peerBluetoothAddress")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error stopping/closing Bluetooth Channel Sounding session ($roleStr)", e)
            }
        }
    }
}

private fun reasonToString(reason: Int): String = when (reason) {
    RangingSession.Callback.REASON_LOCAL_REQUEST -> "REASON_LOCAL_REQUEST ($reason)"
    RangingSession.Callback.REASON_REMOTE_REQUEST -> "REASON_REMOTE_REQUEST ($reason)"
    RangingSession.Callback.REASON_UNSUPPORTED -> "REASON_UNSUPPORTED ($reason)"
    RangingSession.Callback.REASON_SYSTEM_POLICY -> "REASON_SYSTEM_POLICY ($reason)"
    RangingSession.Callback.REASON_NO_PEERS_FOUND -> "REASON_NO_PEERS_FOUND ($reason)"
    RangingSession.Callback.REASON_UNKNOWN -> "REASON_UNKNOWN ($reason)"
    else -> "UNKNOWN ($reason)"
}
