package com.android.identity.android.mdoc.deviceretrieval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.cbor.Simple
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class VerificationHelperTest {

    @Test
    fun testSetDeviceEngagement_ManualInjection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = Executor { it.run() }

        val listener = object : VerificationHelper.Listener {
            override fun onReaderEngagementReady(readerEngagement: ByteArray) {}
            override fun onDeviceEngagementReceived(connectionMethods: List<MdocConnectionMethod>) {}
            override fun onMoveIntoNfcField() {}
            override fun onDeviceConnected() {}
            override fun onResponseReceived(deviceResponseBytes: ByteArray) {}
            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {}
            override fun onError(error: Throwable) {
                throw RuntimeException(error)
            }
        }

        val helper = VerificationHelper.Builder(context, listener, executor).build()

        // ISO 18013-5 Annex D Device Engagement Test Vector (Hex)
        val hex = "a30063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2" +
                "e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a" +
                "8cfcf3de6aa672fc60a557aa32fc670281830201a300f401f50b5045efef742b2c4837a9a" +
                "3b0e1d05a6917"

        val engagementBytes = hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        helper.setDeviceEngagement(
            engagementBytes,
            Simple.NULL,
            VerificationHelper.EngagementMethod.QR_CODE
        )

        assertNotNull(helper.sessionTranscript)
    }
}
