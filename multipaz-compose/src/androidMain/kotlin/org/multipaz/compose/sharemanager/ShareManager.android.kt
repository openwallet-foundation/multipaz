package org.multipaz.compose.sharemanager

import android.content.Intent
import androidx.core.content.FileProvider
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import java.io.File

actual class ShareManager {
    actual suspend fun shareDocument(content: ByteArray, filename: String, mimeType: String, title: String?) {
        val context = AndroidUiContext.current()

        val sharedFolder = File(context.cacheDir, "shared_docs").apply { mkdirs() }
        val file = File(sharedFolder, filename)
        file.writeBytes(content)

        val authority = "${context.packageName}.multipaz.fileprovider"
        val uri = FileProvider.getUriForFile(applicationContext, authority, file)

        // 3. Create the share Intent
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (title != null) putExtra(Intent.EXTRA_TITLE, title)

            // CRITICAL: Grant the target app permission to read this specific URI
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(sendIntent, title)
        context.startActivity(chooserIntent)
    }
}