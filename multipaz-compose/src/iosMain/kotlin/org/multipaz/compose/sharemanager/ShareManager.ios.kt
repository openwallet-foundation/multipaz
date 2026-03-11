package org.multipaz.compose.sharemanager

import platform.Foundation.*
import platform.UIKit.*
import kotlinx.cinterop.*

actual class ShareManager {
    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun shareDocument(content: ByteArray, filename: String, mimeType: String, title: String?) {
        val nsData = content.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), content.size.toULong())
        }

        val tempDir = NSTemporaryDirectory()
        val filePath = tempDir + filename
        nsData.writeToFile(filePath, atomically = true)

        val fileUrl = NSURL.fileURLWithPath(filePath)

        val activityViewController = UIActivityViewController(
            activityItems = listOf(fileUrl),
            applicationActivities = null
        )

        val window = UIApplication.sharedApplication.windows.firstOrNull {
            (it as UIWindow).isKeyWindow()
        } as? UIWindow
        val rootViewController = window?.rootViewController

        val popoverController = activityViewController.popoverPresentationController
        if (popoverController != null && rootViewController != null) {
            popoverController.sourceView = rootViewController.view
            popoverController.sourceRect = rootViewController.view.bounds
            popoverController.permittedArrowDirections = 0UL
        }

        rootViewController?.presentViewController(
            activityViewController,
            animated = true,
            completion = null
        )
    }
}