package org.multipaz.testapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            transportOptions = MdocTransportOptions(
                bleUseL2CAP = app.settingsModel.presentmentBleL2CapEnabled.value,
                bleUseL2CAPInEngagement = app.settingsModel.presentmentBleL2CapInEngagementEnabled.value
            ),
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
            transportOptions = MdocTransportOptions(
                bleUseL2CAP = app.settingsModel.presentmentBleL2CapEnabled.value,
                bleUseL2CAPInEngagement = app.settingsModel.presentmentBleL2CapInEngagementEnabled.value
            ),
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

    fun init(settingsModel: TestAppSettingsModel) {
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        // Apply JVM system property / environment variable overrides if specified
        val envEnabled = System.getProperty("org.multipaz.nfc.enabled")
            ?: System.getenv("MULTIPAZ_NFC_ENABLED")
        if (envEnabled != null && (envEnabled.equals("true", ignoreCase = true) || envEnabled == "1")) {
            settingsModel.nciNfcEnabled.value = true
        }
        (System.getProperty("org.multipaz.nfc.i2cDevice") ?: System.getenv("MULTIPAZ_NFC_I2C_DEV"))
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { settingsModel.nciI2cDevicePath.value = it }
        (System.getProperty("org.multipaz.nfc.i2cAddress") ?: System.getenv("MULTIPAZ_NFC_I2C_ADDR"))
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { settingsModel.nciI2cAddress.value = it }
        (System.getProperty("org.multipaz.nfc.gpioChip") ?: System.getenv("MULTIPAZ_NFC_GPIO_CHIP"))
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { settingsModel.nciGpioChipPath.value = it }
        (System.getProperty("org.multipaz.nfc.irqPin") ?: System.getenv("MULTIPAZ_NFC_IRQ_PIN"))
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { settingsModel.nciIrqGpioPin.value = it }
        (System.getProperty("org.multipaz.nfc.venPin") ?: System.getenv("MULTIPAZ_NFC_VEN_PIN"))
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { settingsModel.nciVenGpioPin.value = it }

        scope.launch {
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
                val enabled = values[0] as Boolean
                val i2cDev = (values[1] as String).trim().ifEmpty { "/dev/i2c-1" }
                val i2cAddrStr = (values[2] as String).trim()
                val i2cAddr = if (i2cAddrStr.startsWith("0x", ignoreCase = true)) {
                    i2cAddrStr.substring(2).toIntOrNull(16) ?: 0x28
                } else {
                    i2cAddrStr.toIntOrNull() ?: 0x28
                }
                val gpioChip = (values[3] as String).trim().ifEmpty { "/dev/gpiochip0" }
                val irqPin = (values[4] as String).trim().toIntOrNull() ?: 23
                val venPin = (values[5] as String).trim().toIntOrNull() ?: 24
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

    private suspend fun reconfigure(config: Config) = mutex.withLock {
        if (config == activeConfig) return
        activeRouter?.let { router ->
            try {
                router.stop()
            } catch (_: Throwable) {
            }
            activeRouter = null
        }
        activeController?.let { controller ->
            try {
                controller.stop()
            } catch (_: Throwable) {
            }
            activeController = null
        }
        activeConfig = config

        if (!config.enabled) {
            Logger.i(TAG, "NCI NFC controller disabled")
            return
        }

        Logger.i(
            TAG,
            "Starting NCI NFC controller on ${config.i2cDevicePath} (addr=0x${config.i2cAddress.toString(16)}, " +
                "chip=${config.gpioChipPath}, irq=${config.irqGpioPin}, ven=${config.venGpioPin})"
        )
        val packetIo = LinuxI2cNciPacketIo(
            i2cDevicePath = config.i2cDevicePath,
            i2cAddress = config.i2cAddress,
            gpioChipPath = config.gpioChipPath,
            irqGpioPin = config.irqGpioPin,
            venGpioPin = config.venGpioPin
        )
        val controller = NciNfcController(packetIo = packetIo)
        val router = JvmTestAppNfcApduRouter()
        try {
            controller.start()
            router.start()
            activeController = controller
            activeRouter = router
            Logger.i(TAG, "NCI NFC controller started (Reader Mode + Card Emulation active)")
        } catch (e: CancellationException) {
            controller.close()
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to start NCI NFC controller", e)
            controller.close()
        }
    }
}
