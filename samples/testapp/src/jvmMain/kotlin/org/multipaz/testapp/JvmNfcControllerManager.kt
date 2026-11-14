package org.multipaz.testapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import org.multipaz.mdoc.nfc.MdocNdefService
import org.multipaz.mdoc.nfc.MdocNfcCceService
import org.multipaz.mdoc.nfc.MdocNfcDataTransferService
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nci.LinuxI2cNciPacketIo
import org.multipaz.nci.NciNfcController
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcApduService
import org.multipaz.presentment.PresentmentModel
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.seconds

private const val TAG = "JvmNfcControllerManager"

private class JvmTestAppMdocNdefService : MdocNdefService() {
    override suspend fun getSettings(): Settings {
        val app = App.getInstance()
        app.initialize()
        val presentmentModel = JvmNfcControllerManager.presentmentModel
        if (!presentmentModel.isActive) {
            presentmentModel.reset(
                source = app.getPresentmentSource(),
                preselectedDocuments = emptyList()
            )
        }
        return Settings(
            source = app.getPresentmentSource(),
            promptModel = app.promptModel,
            presentmentModel = presentmentModel,
            launchUi = {
                Logger.i(TAG, "NFC NDEF tap started mdoc presentment on Desktop JVM")
            },
            sessionEncryptionCurve = app.settingsModel.presentmentSessionEncryptionCurve.value,
            useNegotiatedHandover = app.settingsModel.presentmentUseNegotiatedHandover.value,
            negotiatedHandoverPreferredOrder = app.settingsModel.presentmentNegotiatedHandoverPreferredOrder.value,
            staticHandoverBleCentralClientModeEnabled = app.settingsModel.presentmentBleCentralClientModeEnabled.value,
            staticHandoverBlePeripheralServerModeEnabled = app.settingsModel.presentmentBlePeripheralServerModeEnabled.value,
            staticHandoverNfcDataTransferEnabled = app.settingsModel.presentmentNfcDataTransferEnabled.value,
            bleDirectL2capEnabled = app.settingsModel.presentmentBleL2CapInEngagementEnabled.value,
            transportOptions = MdocTransportOptions(
                bleUseL2CAP = app.settingsModel.presentmentBleL2CapEnabled.value,
            ),
            transportFactory = TestAppConfiguration.mdocTransportFactory,
        )
    }
}

private class JvmTestAppMdocNfcCceService : MdocNfcCceService() {
    override suspend fun getSettings(): Settings {
        val app = App.getInstance()
        app.initialize()
        val presentmentModel = JvmNfcControllerManager.presentmentModel
        if (!presentmentModel.isActive) {
            presentmentModel.reset(
                source = app.getPresentmentSource(),
                preselectedDocuments = emptyList()
            )
        }
        return Settings(
            source = app.getPresentmentSource(),
            promptModel = app.promptModel,
            presentmentModel = presentmentModel,
            launchUi = {
                Logger.i(TAG, "NFC CCE tap started mdoc presentment on Desktop JVM")
            },
            sessionEncryptionCurve = app.settingsModel.presentmentSessionEncryptionCurve.value,
            onlyAllowNfc = app.settingsModel.presentmentNfcCceOnlyAllowNfc.value,
            handoverPreferredOrder = app.settingsModel.presentmentNegotiatedHandoverPreferredOrder.value,
            bleDirectL2capEnabled = app.settingsModel.presentmentBleL2CapInEngagementEnabled.value,
            transportOptions = MdocTransportOptions(
                bleUseL2CAP = app.settingsModel.presentmentBleL2CapEnabled.value,
            ),
            transportFactory = TestAppConfiguration.mdocTransportFactory,
        )
    }
}

private class JvmTestAppNfcApduRouter : NfcApduRouter() {
    override fun buildServices(): Map<ByteString, NfcApduService> {
        return mapOf(
            Nfc.NDEF_APPLICATION_ID to JvmTestAppMdocNdefService(),
            Nfc.ISO_MDOC_NFC_CCE_APPLICATION_ID to JvmTestAppMdocNfcCceService(),
            Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID to MdocNfcDataTransferService()
        )
    }
}

/**
 * Manages the lifecycle of [NciNfcController] and [JvmTestAppNfcApduRouter] on Desktop/Linux JVM
 * according to [TestAppSettingsModel] and environment/system-property overrides.
 */
internal object JvmNfcControllerManager {
    val presentmentModel = PresentmentModel()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var initialized = false
    private var shuttingDown = false
    private var collectJob: Job? = null
    private var activeControllerScope: CoroutineScope? = null
    private var activeController: NciNfcController? = null
    private var activeRouter: JvmTestAppNfcApduRouter? = null
    private var activeConfig: Config? = null

    private data class Config(
        val enabled: Boolean,
        val i2cDevicePath: String,
        val i2cAddress: Int,
        val gpioChipPath: String,
        val irqGpioPin: Int,
        val venGpioPin: Int,
    )

    private fun parseBooleanOverride(raw: String?): Boolean? {
        val trimmed = raw?.trim() ?: return null
        return when {
            trimmed.equals("true", ignoreCase = true) || trimmed == "1" -> true
            trimmed.equals("false", ignoreCase = true) || trimmed == "0" -> false
            else -> null
        }
    }

    fun init(settingsModel: TestAppSettingsModel) {
        synchronized(this) {
            if (initialized || shuttingDown) return
            initialized = true
        }

        // Read transient JVM system property / environment variable overrides without
        // writing them permanently into SQLite-backed TestAppSettingsModel.
        val overrideEnabled = parseBooleanOverride(
            System.getProperty("org.multipaz.nfc.enabled") ?: System.getenv("MULTIPAZ_NFC_ENABLED")
        )
        val overrideI2cDev = (System.getProperty("org.multipaz.nfc.i2cDevice")
            ?: System.getenv("MULTIPAZ_NFC_I2C_DEV"))?.trim()?.takeIf { it.isNotEmpty() }
        val overrideI2cAddr = (System.getProperty("org.multipaz.nfc.i2cAddress")
            ?: System.getenv("MULTIPAZ_NFC_I2C_ADDR"))?.trim()?.takeIf { it.isNotEmpty() }
        val overrideGpioChip = (System.getProperty("org.multipaz.nfc.gpioChip")
            ?: System.getenv("MULTIPAZ_NFC_GPIO_CHIP"))?.trim()?.takeIf { it.isNotEmpty() }
        val overrideIrqPin = (System.getProperty("org.multipaz.nfc.irqPin")
            ?: System.getenv("MULTIPAZ_NFC_IRQ_PIN"))?.trim()?.takeIf { it.isNotEmpty() }
        val overrideVenPin = (System.getProperty("org.multipaz.nfc.venPin")
            ?: System.getenv("MULTIPAZ_NFC_VEN_PIN"))?.trim()?.takeIf { it.isNotEmpty() }

        collectJob = scope.launch {
            combine<Any, Config>(
                listOf(
                    settingsModel.nciNfcEnabled,
                    settingsModel.nciI2cDevicePath,
                    settingsModel.nciI2cAddress,
                    settingsModel.nciGpioChipPath,
                    settingsModel.nciIrqGpioPin,
                    settingsModel.nciVenGpioPin
                )
            ) { values ->
                val enabled = overrideEnabled ?: (values[0] as Boolean)
                val i2cDev = (overrideI2cDev ?: (values[1] as String)).trim().ifEmpty { "/dev/i2c-1" }
                val i2cAddrStr = (overrideI2cAddr ?: (values[2] as String)).trim()
                val i2cAddr = if (i2cAddrStr.startsWith("0x", ignoreCase = true)) {
                    i2cAddrStr.substring(2).toIntOrNull(16) ?: 0x28
                } else {
                    i2cAddrStr.toIntOrNull() ?: 0x28
                }
                val gpioChip = (overrideGpioChip ?: (values[3] as String)).trim().ifEmpty { "/dev/gpiochip0" }
                val irqPin = (overrideIrqPin ?: (values[4] as String)).trim().toIntOrNull() ?: 23
                val venPin = (overrideVenPin ?: (values[5] as String)).trim().toIntOrNull() ?: 24
                Config(
                    enabled = enabled,
                    i2cDevicePath = i2cDev,
                    i2cAddress = i2cAddr,
                    gpioChipPath = gpioChip,
                    irqGpioPin = irqPin,
                    venGpioPin = venPin
                )
            }.collect { config ->
                reconfigure(config)
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            if (shuttingDown) return
            shuttingDown = true
        }
        collectJob?.cancel()
        collectJob = null
        runBlocking {
            withTimeoutOrNull(2.seconds) {
                mutex.withLock {
                    stopActiveResourcesLocked()
                }
            }
        }
        scope.cancel()
    }

    private suspend fun stopActiveResourcesLocked() {
        activeRouter?.let { router ->
            try {
                router.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Error stopping NFC APDU router", e)
            }
            activeRouter = null
        }
        activeController?.let { controller ->
            try {
                controller.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Error stopping NCI NFC controller", e)
            }
            activeController = null
        }
        activeControllerScope?.cancel()
        activeControllerScope = null
    }

    private suspend fun reconfigure(config: Config) = mutex.withLock {
        if (shuttingDown || config == activeConfig) return
        stopActiveResourcesLocked()
        activeConfig = config

        if (!config.enabled) {
            Logger.i(TAG, "NCI NFC controller disabled")
            return
        }

        if (config.i2cAddress !in 0x03..0x77) {
            Logger.e(
                TAG,
                "Refusing to start NCI NFC controller: invalid 7-bit I2C address 0x${config.i2cAddress.toString(16)}"
            )
            return
        }
        if (config.irqGpioPin < 0 || config.venGpioPin < 0 || config.irqGpioPin == config.venGpioPin) {
            Logger.e(
                TAG,
                "Refusing to start NCI NFC controller: invalid GPIO pins (irq=${config.irqGpioPin}, ven=${config.venGpioPin})"
            )
            return
        }

        Logger.i(
            TAG,
            "Starting NCI NFC controller on ${config.i2cDevicePath} (addr=0x${config.i2cAddress.toString(16)}, " +
                "chip=${config.gpioChipPath}, irq=${config.irqGpioPin}, ven=${config.venGpioPin})"
        )
        val controllerScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        val controller = try {
            val packetIo = LinuxI2cNciPacketIo(
                i2cDevicePath = config.i2cDevicePath,
                i2cAddress = config.i2cAddress,
                gpioChipPath = config.gpioChipPath,
                irqGpioPin = config.irqGpioPin,
                venGpioPin = config.venGpioPin
            )
            NciNfcController(packetIo = packetIo, scope = controllerScope)
        } catch (e: Exception) {
            controllerScope.cancel()
            Logger.e(TAG, "Failed to construct NCI NFC controller", e)
            return
        }
        val router = JvmTestAppNfcApduRouter()
        try {
            controller.start()
            router.start()
            activeControllerScope = controllerScope
            activeController = controller
            activeRouter = router
            Logger.i(TAG, "NCI NFC controller started (Reader Mode + Card Emulation active)")
        } catch (e: CancellationException) {
            try {
                router.stop()
            } catch (_: Exception) {
            }
            controller.close()
            controllerScope.cancel()
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start NCI NFC controller", e)
            try {
                router.stop()
            } catch (_: Exception) {
            }
            controller.close()
            controllerScope.cancel()
        }
    }
}
