package org.multipaz.testapp

import android.content.ComponentName
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.applinks.AppLinksCheck
import org.multipaz.context.initializeApplication
import org.multipaz.nfc.handleUsbDeviceAttached
import org.multipaz.testapp.TestAppConfiguration.ACTION_VIEW_DOCUMENT
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
            app.initialize()
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
        if (intent.action == ACTION_VIEW_DOCUMENT) {
            val documentId = intent.getStringExtra("documentId")
            if (documentId != null) {
                lifecycle.coroutineScope.launch {
                    val app = App.getInstance()
                    app.initialize()
                    app.viewDocument(documentId)
                }
            }
        } else if (intent.action == Intent.ACTION_VIEW) {

            intent.data?.let { uri ->
                val mimeType = contentResolver.getType(uri) ?: intent.type
                val fileName = getFileNameFromUri(uri)
                if (mimeType == "application/vnd.multipaz.mpzpass" || fileName?.endsWith(".mpzpass", ignoreCase = true) == true) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            contentResolver.openInputStream(uri)?.use { inputStream ->
                                val fileContent: ByteArray = inputStream.readBytes()
                                val app = App.getInstance()
                                app.importMpzPass(fileContent)
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "Error reading file content from URI", e)
                        }
                    }
                    return
                }
            }

            val url = intent.dataString
            if (url != null) {
                lifecycle.coroutineScope.launch {
                    val app = App.getInstance()
                    app.initialize()
                    app.handleUrl(url)
                }
            }
        } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
            if (device != null) {
                lifecycle.coroutineScope.launch {
                    val app = App.getInstance()
                    app.initialize()
                    app.externalNfcReaderStore.handleUsbDeviceAttached(device)
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null

        // If it's a content URI, query the ContentResolver for the display name
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }

        // Fallback: If it's a file URI or the query failed, try to extract it from the path
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }

        return result
    }}
