package org.multipaz.testapp

import org.multipaz.util.Platform
import org.multipaz.compose.mdoc.MdocNdefService
import org.multipaz.mdoc.transport.MdocTransportOptions

class TestAppMdocNdefService: MdocNdefService() {
    private lateinit var settingsModel: TestAppSettingsModel

    override suspend fun getSettings(): Settings {
        settingsModel = TestAppSettingsModel.create(
            storage = Platform.storage,
            readOnly = true
        )
        platformCryptoInit(settingsModel)

        return Settings(
            sessionEncryptionCurve = settingsModel.presentmentSessionEncryptionCurve.value,
            allowMultipleRequests = settingsModel.presentmentAllowMultipleRequests.value,
            useNegotiatedHandover = settingsModel.presentmentUseNegotiatedHandover.value,
            negotiatedHandoverPreferredOrder = settingsModel.presentmentNegotiatedHandoverPreferredOrder.value,
            staticHandoverBleCentralClientModeEnabled = settingsModel.presentmentBleCentralClientModeEnabled.value,
            staticHandoverBlePeripheralServerModeEnabled = settingsModel.presentmentBlePeripheralServerModeEnabled.value,
            staticHandoverNfcDataTransferEnabled = settingsModel.presentmentNfcDataTransferEnabled.value,
            transportOptions = MdocTransportOptions(
                bleUseL2CAP = settingsModel.presentmentBleL2CapEnabled.value,
                bleUseL2CAPInEngagement = settingsModel.presentmentBleL2CapInEngagementEnabled.value
            ),
            promptModel = platformPromptModel,
            presentmentActivityClass = TestAppMdocNfcPresentmentActivity::class.java
        )
    }
}
