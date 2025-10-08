package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.testapp.multidevicetests.MultiDeviceTestsClient
import org.multipaz.testapp.multidevicetests.MultiDeviceTestsServer
import org.multipaz.testapp.multidevicetests.Plan
import org.multipaz.testapp.multidevicetests.Results
import org.multipaz.util.Logger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.testapp.TestAppConfiguration
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MdocTransportMultiDeviceTestingScreen"

private /* const */ val PREWARMING_DURATION = 6.seconds

@Composable
fun IsoMdocMultiDeviceTestingScreen(
    showToast: (message: String) -> Unit,
) {
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()

    val coroutineScope = rememberCoroutineScope()

    val multiDeviceTestsServerShowButton = remember { mutableStateOf<String?>(null) }
    val multiDeviceTestsClientShowButton = remember { mutableStateOf(false) }

    val multiDeviceTestClient = remember { mutableStateOf<MultiDeviceTestsClient?>(null) }
    val multiDeviceTestServer = remember { mutableStateOf<MultiDeviceTestsServer?>(null) }
    val multiDeviceTestResults = remember { mutableStateOf<Results>(Results()) }

    if (multiDeviceTestsServerShowButton.value != null) {
        val url = multiDeviceTestsServerShowButton.value!!
        Logger.i(TAG, "URL is $url")
        ShowQrCodeDialog(
            title = { Text(text = "Scan QR code") },
            text = { Text(text = "Scan this QR code on another device to run multi-device tests") },
            dismissButton = "Close",
            data = url,
            onDismiss = {
                multiDeviceTestsServerShowButton.value = null
            }
        )
    }

    if (multiDeviceTestsClientShowButton.value) {
        val uriScheme = "owf-ic-testing://"
        ScanQrCodeDialog(
            title = { Text ("Scan QR code") },
            text = { Text ("Scan code from another device to run multi-device tests.") },
            dismissButton = "Close",
            onCodeScanned = { data ->
                if (data.startsWith(uriScheme)) {
                    val serverAndPort = data.substring(uriScheme.length)
                    val colonPos = serverAndPort.indexOf(':')
                    val serverAddress = serverAndPort.substring(0, colonPos)
                    val serverPort = serverAndPort.substring(colonPos + 1, serverAndPort.length).toInt()
                    Logger.i(TAG, "Connect to $serverAddress port $serverPort")

                    coroutineScope.launch {
                        withContext(Dispatchers.Default) {
                            try {
                                val selectorManager = SelectorManager(Dispatchers.Default)
                                val socket = aSocket(selectorManager).tcp().connect(serverAddress, serverPort)
                                runMultiDeviceTestsClient(
                                    socket,
                                    multiDeviceTestClient,
                                    showToast
                                )
                            } catch (error: Throwable) {
                                showToast("Error: ${error.message}")
                            }
                        }
                    }
                    multiDeviceTestsClientShowButton.value = false
                    true
                } else {
                    false
                }
            },
            onDismiss = {
                multiDeviceTestsClientShowButton.value = false
            }
        )
    }

    fun multiDeviceTestListen(plan: Plan) {
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val localAddress = TestAppConfiguration.localIpAddress
                    val selectorManager = SelectorManager(Dispatchers.Default)
                    val serverSocket = aSocket(selectorManager).tcp().bind(localAddress, 0)
                    val localAddressPort = (serverSocket.localAddress as InetSocketAddress).port
                    Logger.i(TAG, "Accepting connections at $localAddress port $localAddressPort")
                    multiDeviceTestsServerShowButton.value =
                        "owf-ic-testing://${localAddress}:${localAddressPort}"
                    val socket = serverSocket.accept()
                    multiDeviceTestsServerShowButton.value = null
                    multiDeviceTestResults.value = Results()
                    runMultiDeviceTestsServer(
                        socket,
                        plan,
                        multiDeviceTestServer,
                        multiDeviceTestResults,
                        showToast
                    )
                } catch (error: Throwable) {
                    showToast("Error: ${error.message}")
                }
            }
        }
    }


    if (!blePermissionState.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        blePermissionState.launchPermissionRequest()
                    }
                }
            ) {
                Text("Request BLE permissions")
            }
        }
    } else if (!bleEnabledState.isEnabled) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        bleEnabledState.enable()
                    }
                }
            ) {
                Text("Enable Bluetooth")
            }
        }
    } else {
        if (multiDeviceTestServer.value != null) {
            val r = multiDeviceTestResults.value
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Running Multi-Device-Test as Server",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text("Plan: ${r.plan.description}")
                if (r.currentTest != null) {
                    Text("Currently Testing: ${r.currentTest.description}")
                }
                Spacer(modifier = Modifier.height(10.dp))

                Text("Number Iterations Completed: ${r.numIterationsCompleted} of ${r.numIterationsTotal}")
                Text("Number Iterations Successful: ${r.numIterationsSuccessful}")
                Text("Failed Iterations: ${r.failedIterations}")
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Transaction Time Avg: ${r.transactionTime.avg} msec",
                    fontWeight = FontWeight.Bold,
                )
                Text("min/max/σ: ${r.transactionTime.min}/${r.transactionTime.max}/${r.transactionTime.stdDev}")
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Scanning Time Avg: ${r.scanningTime.avg} msec",
                    fontWeight = FontWeight.Bold,
                )
                Text("min/max/σ: ${r.scanningTime.min}/${r.scanningTime.max}/${r.scanningTime.stdDev}")

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        multiDeviceTestServer.value?.job?.cancel()
                        multiDeviceTestServer.value = null
                    }
                ) {
                    if (r.numIterationsTotal > 0 && r.numIterationsCompleted == r.numIterationsTotal) {
                        Text("Close")
                    } else {
                        Text("Cancel")
                    }
                }
            }
        } else if (multiDeviceTestClient.value != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Running Multi-Device-Test as Client",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        multiDeviceTestClient.value?.job?.cancel()
                        multiDeviceTestClient.value = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    TextButton(
                        onClick = {
                            multiDeviceTestsClientShowButton.value = true
                        },
                        content = { Text("Run Multi-Device Tests as Client") }
                    )
                }

                for (plan in Plan.entries) {
                    val numIt = plan.tests.sumOf { it.second }
                    item {
                        TextButton(
                            onClick = { multiDeviceTestListen(plan) },
                            content = {
                                Text("Plan: ${plan.description} ($numIt iterations)")
                            }
                        )
                    }
                }
            }
        }
    }
}

private suspend fun runMultiDeviceTestsServer(
    socket: Socket,
    plan: Plan,
    multiDeviceTestsServer: MutableState<MultiDeviceTestsServer?>,
    multiDeviceTestsResults: MutableState<Results>,
    showToast: (message: String) -> Unit
) {
    val server = MultiDeviceTestsServer(
        coroutineContext.job,
        socket,
        plan,
        multiDeviceTestsResults
    )
    multiDeviceTestsServer.value = server
    try {
        server.run()
    } catch (error: Throwable) {
        Logger.i(TAG, "Multi-Device Tests failed", error)
        error.printStackTrace()
        showToast("Multi-Device-Tests failed: ${error.message}")
    }
    // Keep multiDeviceTestServer around so user has a chance to review the results...
    // multiDeviceTestsServer.value = null
}

private suspend fun runMultiDeviceTestsClient(
    socket: Socket,
    multiDeviceTestsClient: MutableState<MultiDeviceTestsClient?>,
    showToast: (message: String) -> Unit
) {
    val client = MultiDeviceTestsClient(
        coroutineContext.job,
        socket,
    )
    multiDeviceTestsClient.value = client
    try {
        client.run()
    } catch (error: Throwable) {
        Logger.i(TAG, "Multi-Device Tests failed", error)
        error.printStackTrace()
        showToast("Multi-Device-Tests failed: ${error.message}")
    }
    multiDeviceTestsClient.value = null
}
