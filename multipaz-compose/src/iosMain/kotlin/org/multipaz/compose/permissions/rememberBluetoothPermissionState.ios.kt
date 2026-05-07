package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.darwin.NSObject

private const val TAG = "BluetoothPermissionState.ios"

private class IosBluetoothPermissionState(
    val recomposeCounter: MutableIntState,
    val scope: CoroutineScope
) : PermissionState {

    override val isGranted: Boolean
        get() = (CBManager.authorization == CBManagerAuthorizationAllowedAlways)

    override val isPermanentlyDenied: Boolean
        get() {
            val auth = CBManager.authorization
            return auth == CBManagerAuthorizationDenied || auth == CBManagerAuthorizationRestricted
        }

    override suspend fun launchPermissionRequest() {
        CBCentralManager(object : NSObject(), CBCentralManagerDelegateProtocol {
            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                scope.launch(Dispatchers.Main) {
                    recomposeCounter.value = recomposeCounter.value + 1
                }
            }
        }, null)
    }
}

@Composable
actual fun rememberBluetoothPermissionState(): PermissionState {
    val scope = rememberCoroutineScope()
    val recomposeCounter = remember { mutableIntStateOf(0) }
    LaunchedEffect(recomposeCounter.value) {}

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            recomposeCounter.value = recomposeCounter.value + 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return IosBluetoothPermissionState(recomposeCounter, scope)
}

