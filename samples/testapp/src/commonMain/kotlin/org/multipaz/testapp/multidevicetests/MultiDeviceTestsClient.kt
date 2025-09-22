package org.multipaz.testapp.multidevicetests

import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.engagement.DeviceEngagement
import kotlin.time.Clock
import org.multipaz.mdoc.role.MdocRole
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class MultiDeviceTestsClient(
    val job: Job,
    val socket: Socket,
) {
    companion object {
        private const val TAG = "MultiDeviceTestsClient"
        private val LINE_LIMIT = 4096
    }

    val receiveChannel = socket.openReadChannel()
    val sendChannel = socket.openWriteChannel(autoFlush = true)

    suspend fun run() {
        // Client listens for commands from server.
        while (true) {
            val cmd = receiveChannel.readUTF8Line(LINE_LIMIT)
            Logger.i(TAG, "Received command '$cmd'")
            if (cmd == null) {
                throw Error("Receive channel was closed")
            } else if (cmd == "Done") {
                break
            } else if (cmd.startsWith("TestPresentationPrepare ")) {
                val parts = cmd.split(" ")
                val iterationNumber = parts[1].toInt()
                val numIterationsTotal = parts[2].toInt()
                val testName = parts[3]
                val usePrewarming = if (parts[4] == "true") true else false
                val bleUseL2CAP = if (parts[5] == "true") true else false
                val getPsmFromReader = if (parts[6] == "true") true else false
                val encodedDeviceEngagement = parts[7].fromBase64Url()
                val test = Test.valueOf(testName)
                val options = MdocTransportOptions(
                    bleUseL2CAP = bleUseL2CAP,
                    bleUseL2CAPInEngagement = bleUseL2CAP,
                )

                Logger.i(TAG, "====== STARTING ITERATION ${iterationNumber} OF ${numIterationsTotal} ======")
                Logger.i(TAG, "Test: $test")
                Logger.iHex(TAG, "DeviceEngagement from server", encodedDeviceEngagement)
                val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement))
                val connectionMethod = deviceEngagement.connectionMethods[0]
                val eDeviceKey = deviceEngagement.eDeviceKey
                val transport = MdocTransportFactory.Default.createTransport(
                    connectionMethod,
                    MdocRole.MDOC_READER,
                    options
                )
                Logger.i(TAG, "usePrewarming: $usePrewarming")
                if (usePrewarming) {
                    transport.advertise()
                }
                if (getPsmFromReader) {
                    val cm = transport.connectionMethod as MdocConnectionMethodBle
                    sendChannel.writeStringUtf8("${cm.peripheralServerModePsm!!}\n")
                }
                val nextCmd = receiveChannel.readUTF8Line(LINE_LIMIT)
                if (nextCmd != "TestPresentationStart") {
                    throw IllegalStateException("Expected 'TestPresentationStart' got $nextCmd")
                }
                Logger.i(TAG, "Starting")
                try {
                    withTimeout(15.seconds) {
                        val timeStart = Clock.System.now()
                        transport.open(eDeviceKey)

                        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
                        val encodedSessionTranscript = byteArrayOf(0x01, 0x02)
                        val sessionEncryption = SessionEncryption(
                            role = MdocRole.MDOC_READER,
                            eSelfKey = eReaderKey,
                            remotePublicKey = eDeviceKey,
                            encodedSessionTranscript = encodedSessionTranscript
                        )
                        val deviceRequest = ByteArray(2 * 1024)
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceRequest,
                                statusCode = null
                            )
                        )
                        val response = transport.waitForMessage()
                        val (deviceResponse, statusCode) = sessionEncryption.decryptMessage(response)
                        when (test) {
                            Test.MDOC_CENTRAL_CLIENT_MODE,
                            Test.MDOC_PERIPHERAL_SERVER_MODE,
                            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP,
                            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_PSM_IN_TWO_WAY_ENGAGEMENT,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_PSM_IN_DEVICE_ENGAGEMENT -> {
                                // Expects termination in initial message
                                if (statusCode != Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                                    throw Error("Expected status 20, got $statusCode")
                                }
                            }
                            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_MSG,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_MSG,
                            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_HOLDER_TERMINATION_MSG,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_HOLDER_TERMINATION_MSG -> {
                                if (statusCode != null) {
                                    throw Error("Expected status to be unset got $statusCode")
                                }
                                val (deviceResponse, statusCode) = sessionEncryption.decryptMessage(
                                    transport.waitForMessage()
                                )
                                if (deviceResponse != null ||
                                    statusCode != Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                                    throw Error("Expected empty message and status 20")
                                }
                            }
                            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_BLE,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_BLE -> {
                                if (statusCode != null) {
                                    throw Error("Expected status to be unset got $statusCode")
                                }
                                // Expects a termination via BLE
                                val sessionData = transport.waitForMessage()
                                if (sessionData.isNotEmpty()) {
                                    throw Error("Expected transport-specific termination, got non-empty message")
                                }
                            }
                            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_MSG,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_MSG,
                            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_READER_TERMINATION_MSG,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_READER_TERMINATION_MSG -> {
                                if (statusCode != null) {
                                    throw Error("Expected status to be unset got $statusCode")
                                }
                                // Expects reader to terminate via message
                                transport.sendMessage(
                                    SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                                )
                            }
                            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_BLE,
                            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_BLE -> {
                                if (statusCode != null) {
                                    throw Error("Expected status to be unset got $statusCode")
                                }
                                // Expects reader to terminate via BLE
                                transport.sendMessage(byteArrayOf())
                            }
                        }

                        val timeEnd = Clock.System.now()
                        val timeEngagementToResponseMsec = (timeEnd - timeStart).toInt(DurationUnit.MILLISECONDS)
                        val scanningTimeMsec = transport.scanningTime?.toInt(DurationUnit.MILLISECONDS) ?: 0
                        sendChannel.writeStringUtf8("TestPresentationSuccess $timeEngagementToResponseMsec " +
                                "$scanningTimeMsec\n")
                    }
                } catch (e: TimeoutCancellationException) {
                    sendChannel.writeStringUtf8("TestPresentationTimeout\n")
                } catch (e: Throwable) {
                    Logger.w(TAG, "Iteration failed", e)
                    e.printStackTrace()
                    sendChannel.writeStringUtf8("TestPresentationFailed\n")
                } finally {
                    transport.close()
                }

            } else {
                throw Error("Unknown command $cmd")
            }
        }
    }
}
