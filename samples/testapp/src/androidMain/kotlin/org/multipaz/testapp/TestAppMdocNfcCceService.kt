package org.multipaz.testapp

import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.mdoc.nfc.MdocNfcCceService
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "TestAppMdocNfcCceService"

class TestAppMdocNfcCceService : MdocNfcCceService() {

    override suspend fun getSettings(): Settings {
        // TODO: optimize initialization of App so we can just get settingsModel and presentmentSource() out
        val t0 = Clock.System.now()
        val app = App.getInstance()
        app.initialize()
        val t1 = Clock.System.now()
        Logger.i(TAG, "App initialized in ${(t1 - t0).inWholeMilliseconds} ms")

        TestAppConfiguration.cryptoInit(app.settingsModel)

        val source = app.getPresentmentSource()
        if (!PresentmentActivity.presentmentModel.isActive) {
            PresentmentActivity.presentmentModel.reset(
                source = source,
                // TODO: if user is currently selecting a document, pass it here
                preselectedDocuments = emptyList()
            )
        }

        return Settings(
            source = app.getPresentmentSource(),
            promptModel = PresentmentActivity.promptModel,
            presentmentModel = PresentmentActivity.presentmentModel,
            launchUi = { PresentmentActivity.startActivity() },
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
