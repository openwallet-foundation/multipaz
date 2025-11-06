package org.multipaz.testapp

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.EcCurve
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.buildCborArray
import org.multipaz.digitalcredentials.Default
import org.multipaz.digitalcredentials.DigitalCredentials
import kotlin.Boolean

/**
 * A model for settings for samples/testapp.
 *
 * TODO: Port [org.multipaz.testapp.ui.ProvisioningTestScreen] to use this.
 */
class TestAppSettingsModel private constructor(
    private val readOnly: Boolean
) {

    private lateinit var settingsTable: StorageTable

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "TestAppSettings",
            supportPartitions = false,
            supportExpiration = false
        )

        /**
         * Asynchronous construction.
         *
         * @param storage the [Storage] backing the settings.
         * @param readOnly if `false`, won't monitor all the settings and write to storage when they change.
         */
        suspend fun create(
            storage: Storage,
            readOnly: Boolean = false
        ): TestAppSettingsModel {
            val instance = TestAppSettingsModel(readOnly)
            instance.settingsTable = storage.getTable(tableSpec)
            instance.init()
            return instance
        }
    }

    private data class BoundItem<T>(
        val variable: MutableStateFlow<T>,
        val defaultValue: T
    ) {
        fun resetValue() {
            variable.value = defaultValue
        }
    }

    private val boundItems = mutableListOf<BoundItem<*>>()

    private suspend inline fun<reified T> bind(
        variable: MutableStateFlow<T>,
        key: String,
        defaultValue: T
    ) {
        val value = settingsTable.get(key)?.let {
            val dataItem = Cbor.decode(it.toByteArray())
            when (T::class) {
                Boolean::class -> { dataItem.asBoolean as T }
                String::class -> { dataItem.asTstr as T }
                List::class -> { dataItem.asArray.map { item -> (item as Tstr).value } as T }
                Set::class -> { dataItem.asArray.map { item -> (item as Tstr).value }.toSet() as T }
                EcCurve::class -> { EcCurve.entries.find { it.name == dataItem.asTstr } as T }
                else -> { throw IllegalStateException("Type not supported") }
            }
        } ?: defaultValue
        variable.value = value

        if (!readOnly) {
            CoroutineScope(Dispatchers.Default).launch {
                variable.asStateFlow().collect { newValue ->
                    val dataItem = when (T::class) {
                        Boolean::class -> {
                            (newValue as Boolean).toDataItem()
                        }

                        String::class -> {
                            (newValue as String).toDataItem()
                        }

                        List::class -> {
                            buildCborArray {
                                (newValue as List<*>).forEach { add(Tstr(it as String)) }
                            }
                        }

                        Set::class -> {
                            buildCborArray {
                                (newValue as Set<*>).forEach { add(Tstr(it as String)) }
                            }
                        }

                        EcCurve::class -> {
                            (newValue as EcCurve).name.toDataItem()
                        }

                        else -> {
                            throw IllegalStateException("Type not supported")
                        }
                    }
                    if (settingsTable.get(key) == null) {
                        settingsTable.insert(key, ByteString(Cbor.encode(dataItem)))
                    } else {
                        settingsTable.update(key, ByteString(Cbor.encode(dataItem)))
                    }
                }
            }
        }
        boundItems.add(BoundItem(variable, defaultValue))
    }

    fun resetSettings() {
        boundItems.forEach { it.resetValue() }
    }

    // TODO: use something like KSP to avoid having to repeat settings name three times..
    //

    private suspend fun init() {
        bind(presentmentBleCentralClientModeEnabled, "presentmentBleCentralClientModeEnabled", false)
        bind(presentmentBlePeripheralServerModeEnabled, "presentmentBlePeripheralServerModeEnabled", true)
        bind(presentmentNfcDataTransferEnabled, "presentmentNfcDataTransferEnabled", false)
        bind(presentmentSessionEncryptionCurve, "presentmentSessionEncryptionCurve", EcCurve.P256)
        bind(presentmentBleL2CapEnabled, "presentmentBleL2CapEnabled", false)
        bind(presentmentBleL2CapInEngagementEnabled, "presentmentBleL2CapInEngagementEnabled", true)
        bind(presentmentUseNegotiatedHandover, "presentmentUseNegotiatedHandover", true)
        bind(presentmentAllowMultipleRequests, "presentmentAllowMultipleRequests", false)
        bind(presentmentNegotiatedHandoverPreferredOrder, "presentmentNegotiatedHandoverPreferredOrder",
            listOf(
                "ble:central_client_mode:",
                "ble:peripheral_server_mode:",
                "nfc:"
            )
        )
        bind(presentmentShowConsentPrompt, "presentmentShowConsentPrompt", true)
        bind(presentmentRequireAuthentication, "presentmentRequireAuthentication", true)
        bind(presentmentPreferSignatureToKeyAgreement, "presentmentPreferSignatureToKeyAgreement", false)

        bind(readerBleCentralClientModeEnabled, "readerBleCentralClientModeEnabled", true)
        bind(readerBlePeripheralServerModeEnabled, "readerBlePeripheralServerModeEnabled", true)
        bind(readerNfcDataTransferEnabled, "readerNfcDataTransferEnabled", true)
        bind(readerBleL2CapEnabled, "readerBleL2CapEnabled", false)
        bind(readerBleL2CapInEngagementEnabled, "readerBleL2CapInEngagementEnabled", true)
        bind(readerAutomaticallySelectTransport, "readerAutomaticallySelectTransport", false)
        bind(readerAllowMultipleRequests, "readerAllowMultipleRequests", false)

        bind(cloudSecureAreaUrl, "cloudSecureAreaUrl", CSA_URL_DEFAULT)
        bind(dcApiProtocols, "dcApiProtocols", DigitalCredentials.Default.supportedProtocols)

        bind(cryptoPreferBouncyCastle, "cryptoForceBouncyCastle", false)

        bind(observeModeEnabled, "observeModeEnabled", false)
        bind(observeModeEmitPollingFramesAsReader, "observeModeEmitPollingFramesAsReader", false)
    }

    val presentmentBleCentralClientModeEnabled = MutableStateFlow<Boolean>(false)
    val presentmentBlePeripheralServerModeEnabled = MutableStateFlow<Boolean>(false)
    val presentmentNfcDataTransferEnabled = MutableStateFlow<Boolean>(false)
    val presentmentSessionEncryptionCurve = MutableStateFlow<EcCurve>(EcCurve.P256)
    val presentmentBleL2CapEnabled = MutableStateFlow<Boolean>(false)
    val presentmentBleL2CapInEngagementEnabled = MutableStateFlow<Boolean>(false)
    val presentmentUseNegotiatedHandover = MutableStateFlow<Boolean>(false)
    val presentmentAllowMultipleRequests = MutableStateFlow<Boolean>(false)
    val presentmentNegotiatedHandoverPreferredOrder = MutableStateFlow<List<String>>(listOf())
    val presentmentShowConsentPrompt = MutableStateFlow<Boolean>(false)
    val presentmentRequireAuthentication = MutableStateFlow<Boolean>(false)
    val presentmentPreferSignatureToKeyAgreement = MutableStateFlow<Boolean>(false)

    val readerBleCentralClientModeEnabled = MutableStateFlow<Boolean>(false)
    val readerBlePeripheralServerModeEnabled = MutableStateFlow<Boolean>(false)
    val readerNfcDataTransferEnabled = MutableStateFlow<Boolean>(false)
    val readerBleL2CapEnabled = MutableStateFlow<Boolean>(false)
    val readerBleL2CapInEngagementEnabled = MutableStateFlow<Boolean>(false)
    val readerAutomaticallySelectTransport = MutableStateFlow<Boolean>(false)
    val readerAllowMultipleRequests = MutableStateFlow<Boolean>(false)

    val cloudSecureAreaUrl = MutableStateFlow<String>(CSA_URL_DEFAULT)
    val dcApiProtocols = MutableStateFlow<Set<String>>(DigitalCredentials.Default.supportedProtocols)

    val cryptoPreferBouncyCastle = MutableStateFlow<Boolean>(false)

    val observeModeEnabled = MutableStateFlow<Boolean>(false)
    val observeModeEmitPollingFramesAsReader = MutableStateFlow<Boolean>(true)
}

// Default to our open CSA, where "open" means it'll work with even unlocked bootloaders
// and any application signing key.
//
private val CSA_URL_DEFAULT: String = "https://csa.multipaz.org/open"
