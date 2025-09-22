package org.multipaz.testapp.multidevicetests

import androidx.compose.runtime.MutableState
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toBase64Url
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class MultiDeviceTestsServer(
    val job: Job,
    val socket: Socket,
    val plan: Plan,
    val multiDeviceTestsResults: MutableState<Results>
) {
    companion object {
        private const val TAG = "MultiDeviceTestsServer"
        private val LINE_LIMIT = 4096
    }

    val receiveChannel = socket.openReadChannel()
    val sendChannel = socket.openWriteChannel(autoFlush = true)

    private var numIterationsTotal = 0
    private var numIterations = 0
    private var numSuccess = 0
    private var failedTransactions = mutableListOf<Int>()
    private var numHolderTimeouts = 0
    private var numHolderErrors = 0
    private var numReaderTimeouts = 0
    private var numReaderErrors = 0
    private var recordedTimeEngagementToResponseMsec = mutableListOf<Double>()
    private var recordedScanningTimeMsec = mutableListOf<Double>()

    suspend fun run() {
        numIterationsTotal = plan.tests.sumOf { it.second }
        multiDeviceTestsResults.value = Results(
            plan = plan,
            numIterationsTotal = numIterationsTotal,
        )
        for ((test, numIterationsForTest) in plan.tests) {
            for (n in IntRange(1, numIterationsForTest)) {
                testPresentation(test)
            }
        }

        updateResults(null)

        sendChannel.writeStringUtf8("Done\n")
    }

    private suspend fun testPresentation(test: Test) {
        updateResults(test)
        val connectionMethod = when (test) {
            Test.MDOC_CENTRAL_CLIENT_MODE,
            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_BLE,
            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_BLE,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_HOLDER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_READER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_PSM_IN_TWO_WAY_ENGAGEMENT -> {
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = false,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = null,
                    centralClientModeUuid = UUID.randomUUID(),
                )
            }
            Test.MDOC_PERIPHERAL_SERVER_MODE,
            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_BLE,
            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_BLE,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_HOLDER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_READER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_PSM_IN_DEVICE_ENGAGEMENT -> {
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = true,
                    supportsCentralClientMode = false,
                    peripheralServerModeUuid = UUID.randomUUID(),
                    centralClientModeUuid = null,
                )
            }
        }

        val bleUseL2CAP = when (test) {
            Test.MDOC_CENTRAL_CLIENT_MODE,
            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_BLE,
            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_BLE,
            Test.MDOC_PERIPHERAL_SERVER_MODE,
            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_BLE,
            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_BLE -> {
                false
            }
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_HOLDER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_READER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_HOLDER_TERMINATION_MSG,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_READER_TERMINATION_MSG,
            Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_PSM_IN_TWO_WAY_ENGAGEMENT,
            Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_PSM_IN_DEVICE_ENGAGEMENT -> {
                true
            }
        }
        val options = MdocTransportOptions(
            bleUseL2CAP = bleUseL2CAP,
            bleUseL2CAPInEngagement = bleUseL2CAP
        )

        var holderSuccess = false
        var readerSuccess = false
        var holderScanningTimeMsec = 0
        var readerScanningTimeMsec = 0

        val iterationNumber = numIterations + 1
        Logger.i(TAG, "====== STARTING ITERATION ${iterationNumber} OF ${numIterationsTotal} ======")

        var transport = MdocTransportFactory.Default.createTransport(
            connectionMethod = connectionMethod,
            role = MdocRole.MDOC,
            options = options
        )
        try {
            if (plan.prewarm) {
                transport.advertise()
            }
            val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val encodedDeviceEngagement = Cbor.encode(buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
                addConnectionMethod(transport.connectionMethod)
            }.toDataItem())
            val getPsmFromReader = (test == Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_PSM_IN_TWO_WAY_ENGAGEMENT)
            sendChannel.writeStringUtf8("TestPresentationPrepare ${iterationNumber} ${numIterationsTotal} " +
                    "${test.name} ${plan.prewarm} ${bleUseL2CAP} ${getPsmFromReader} " +
                    "${encodedDeviceEngagement.toBase64Url()}\n")
            val prewarmDuration = 6.seconds
            Logger.i(TAG, "Waiting $prewarmDuration for pre-warming connection")
            if (getPsmFromReader) {
                val psmFromReader = receiveChannel.readUTF8Line(LINE_LIMIT)!!.toInt()
                Logger.i(TAG, "psmFromReader: $psmFromReader")
                val connectionMethodWithPsm = MdocConnectionMethodBle(
                    supportsPeripheralServerMode = connectionMethod.supportsPeripheralServerMode,
                    supportsCentralClientMode = connectionMethod.supportsCentralClientMode,
                    peripheralServerModeUuid = connectionMethod.peripheralServerModeUuid,
                    centralClientModeUuid = connectionMethod.centralClientModeUuid,
                    peripheralServerModePsm = psmFromReader,
                    peripheralServerModeMacAddress = null
                )
                transport = MdocTransportFactory.Default.createTransport(
                    connectionMethod = connectionMethodWithPsm,
                    role = MdocRole.MDOC,
                    options = options
                )
            }
            delay(prewarmDuration)
            Logger.i(TAG, "Done waiting")
            withTimeout(15.seconds) {
                sendChannel.writeStringUtf8("TestPresentationStart\n")
                transport.open(eDeviceKey.publicKey)
                val sessionEstablishmentMessage = transport.waitForMessage()
                val eReaderKey = SessionEncryption.getEReaderKey(sessionEstablishmentMessage)
                val encodedSessionTranscript = byteArrayOf(0x01, 0x02)
                val sessionEncryption = SessionEncryption(
                    role = MdocRole.MDOC,
                    eSelfKey = eDeviceKey,
                    remotePublicKey = eReaderKey.publicKey,
                    encodedSessionTranscript = encodedSessionTranscript
                )
                val (deviceRequest, statusCode) = sessionEncryption.decryptMessage(sessionEstablishmentMessage)
                val deviceResponse = ByteArray(20 * 1024)

                when (test) {
                    Test.MDOC_CENTRAL_CLIENT_MODE,
                    Test.MDOC_PERIPHERAL_SERVER_MODE,
                    Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP,
                    Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_PSM_IN_TWO_WAY_ENGAGEMENT,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_PSM_IN_DEVICE_ENGAGEMENT -> {
                        // Sends termination in initial message
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceResponse,
                                statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                            )
                        )
                    }
                    Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_MSG,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_MSG,
                    Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_HOLDER_TERMINATION_MSG,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_HOLDER_TERMINATION_MSG -> {
                        // Sends a separate termination message
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceResponse,
                                statusCode = null
                            )
                        )
                        transport.sendMessage(
                            SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                        )
                    }
                    Test.MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_BLE,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_BLE -> {
                        // Sends termination via BLE
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceResponse,
                                statusCode = null
                            )
                        )
                        transport.sendMessage(byteArrayOf())
                    }
                    Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_MSG,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_MSG,
                    Test.MDOC_CENTRAL_CLIENT_MODE_L2CAP_READER_TERMINATION_MSG,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_L2CAP_READER_TERMINATION_MSG -> {
                        // Expects reader to terminate via message
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceResponse,
                                statusCode = null
                            )
                        )
                        val (deviceRequest, statusCode) = sessionEncryption.decryptMessage(transport.waitForMessage())
                        if (deviceRequest != null || statusCode != Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                            throw Error("Expected empty message and status 20")
                        }
                    }
                    Test.MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_BLE,
                    Test.MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_BLE -> {
                        // Expects reader to terminate via BLE
                        transport.sendMessage(
                            sessionEncryption.encryptMessage(
                                messagePlaintext = deviceResponse,
                                statusCode = null
                            )
                        )
                        val sessionData = transport.waitForMessage()
                        if (sessionData.isNotEmpty()) {
                            throw Error("Expected transport-specific termination, got non-empty message")
                        }
                    }
                }

                holderSuccess = true
                holderScanningTimeMsec = transport.scanningTime?.toInt(DurationUnit.MILLISECONDS) ?: 0
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "Iteration timeout")
            numHolderTimeouts += 1
        } catch (e: Throwable) {
            Logger.w(TAG, "Iteration failed", e)
            e.printStackTrace()
            numHolderErrors += 1
        } finally {
            transport.close()
        }

        val response = receiveChannel.readUTF8Line(LINE_LIMIT)
        Logger.i(TAG, "Response from client: $response")
        if (response?.startsWith("TestPresentationSuccess ") == true) {
            readerSuccess = true
            val parts = response.split(" ")
            val timeEngagementToResponseMsec = parts[1].toInt()
            recordedTimeEngagementToResponseMsec.add(timeEngagementToResponseMsec.toDouble())
            readerScanningTimeMsec = parts[2].toInt()
        } else if (response == "TestPresentationTimeout") {
            numReaderTimeouts += 1
        } else if (response == "TestPresentationFailed") {
            numReaderErrors += 1
        } else {
            throw Error("Unexpected TestPresentation response '$response'")
        }

        if (holderScanningTimeMsec > 0 && readerScanningTimeMsec > 0) {
            Logger.w(TAG, "Both holder and reader scanning times are non-zero")
        }
        if (holderScanningTimeMsec > 0) {
            recordedScanningTimeMsec.add(holderScanningTimeMsec.toDouble())
        } else if (readerScanningTimeMsec > 0) {
            recordedScanningTimeMsec.add(readerScanningTimeMsec.toDouble())
        }

        numIterations += 1
        if (readerSuccess && holderSuccess) {
            numSuccess += 1
        } else {
            failedTransactions.add(iterationNumber)
        }
    }

    private fun updateResults(currentTest: Test?) {
        Logger.i(TAG, "it=$numIterations success=$numSuccess " +
                "hTO=$numHolderTimeouts hErr=$numHolderErrors " +
                "rTO=$numReaderTimeouts rErr=$numReaderErrors " +
                "| time: " +
                "min=${recordedTimeEngagementToResponseMsec.minOrZero().toInt()} " +
                "max=${recordedTimeEngagementToResponseMsec.maxOrZero().toInt()} " +
                "avg=${recordedTimeEngagementToResponseMsec.averageOrZero().toInt()} " +
                "stddev=${recordedTimeEngagementToResponseMsec.stdDevOrZero().toInt()} " +
                "| scanning: " +
                "min=${recordedScanningTimeMsec.minOrZero().toInt()} " +
                "max=${recordedScanningTimeMsec.maxOrZero().toInt()} " +
                "avg=${recordedScanningTimeMsec.averageOrZero().toInt()} " +
                "stddev=${recordedScanningTimeMsec.stdDevOrZero().toInt()}")

        multiDeviceTestsResults.value = Results(
            plan = plan,
            currentTest = currentTest,
            numIterationsTotal = numIterationsTotal,
            numIterationsCompleted = numIterations,
            numIterationsSuccessful = numSuccess,
            failedIterations = failedTransactions.toList(),
            transactionTime = Timing(
                min = recordedTimeEngagementToResponseMsec.minOrZero().toInt(),
                max = recordedTimeEngagementToResponseMsec.maxOrZero().toInt(),
                avg = recordedTimeEngagementToResponseMsec.averageOrZero().toInt(),
                stdDev = recordedTimeEngagementToResponseMsec.stdDevOrZero().toInt(),
            ),
            scanningTime = Timing(
                min = recordedScanningTimeMsec.minOrZero().toInt(),
                max = recordedScanningTimeMsec.maxOrZero().toInt(),
                avg = recordedScanningTimeMsec.averageOrZero().toInt(),
                stdDev = recordedScanningTimeMsec.stdDevOrZero().toInt(),
            ),
        )
    }
}

private fun List<Double>.minOrZero(): Double {
    if (isEmpty()) {
        return 0.0
    }
    return this.min()
}

private fun List<Double>.maxOrZero(): Double {
    if (isEmpty()) {
        return 0.0
    }
    return this.max()
}

private fun List<Double>.averageOrZero(): Double {
    if (isEmpty()) {
        return 0.0
    }
    return this.average()
}

private fun List<Double>.stdDevOrZero(): Double {
    if (isEmpty()) {
        return 0.0
    }
    val mean = average()
    val sumOfSquaredDeviations = sumOf { (it - mean) * (it - mean) }
    return sqrt(sumOfSquaredDeviations / size)
}