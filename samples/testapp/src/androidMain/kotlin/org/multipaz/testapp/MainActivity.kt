package org.multipaz.testapp

import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.launch
import org.multipaz.applinks.AppLinksCheck
import org.multipaz.compose.mdoc.MdocNdefService
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.testapp.provisioning.ProvisioningSupport
import org.multipaz.util.Logger

class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
            val cardEmulation = CardEmulation.getInstance(adapter)
            val componentName = ComponentName(this, TestAppMdocNdefService::class.java)
            if (!cardEmulation.setPreferredService(this, componentName)) {
                Logger.w(TAG, "CardEmulation.setPreferredService() returned false")
            }
            if (!cardEmulation.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_OTHER)) {
                Logger.w(TAG, "CardEmulation.categoryAllowsForegroundPreference(CATEGORY_OTHER) returned false")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let {
            val cardEmulation = CardEmulation.getInstance(it)
            if (!cardEmulation.unsetPreferredService(this)) {
                Logger.w(TAG, "CardEmulation.unsetPreferredService() return false")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        lifecycle.coroutineScope.launch {
            val app = App.getInstance()
            app.init()
            app.startExportDocumentsToDigitalCredentials()
            setContent {
                app.Content()
            }
            handleIntent(intent)
            val appLinksSetupIsValid = AppLinksCheck.checkAppLinksServerSetup(
                applicationContext,
                ProvisioningSupport.APP_LINK_SERVER,
                HttpClient(Android)
            )
            if (!appLinksSetupIsValid) {
                Toast.makeText(
                    this@MainActivity,
                    "App links setup is wrong, see logs: 'adb logcat -s AppLinksCheck'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val url = intent.dataString
            if (url != null) {
                lifecycle.coroutineScope.launch {
                    val app = App.getInstance()
                    app.init()
                    app.handleUrl(url)
                }
            }
        }
    }
}
