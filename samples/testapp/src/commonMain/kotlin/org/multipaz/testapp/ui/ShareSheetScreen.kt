package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multipazproject.samples.testapp.generated.resources.Res
import org.multipaz.compose.rememberUiBoundCoroutineScope
import org.multipaz.compose.sharemanager.ShareManager
import org.multipaz.prompt.PromptModel
import kotlin.time.Clock

@Composable
fun ShareSheetScreen(
    promptModel: PromptModel,
    onBack: () -> Unit,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberUiBoundCoroutineScope { promptModel }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        val now = Clock.System.now()
                        val shareManager = ShareManager()
                        shareManager.shareDocument(
                            content = "Hello World, this is a test, time is ${now.toString()}".encodeToByteArray(),
                            filename = "test-share.txt",
                            mimeType = "text/plain",
                            title = "Test sharing of text/plain"
                        )
                    }
                },
                content = { Text("Share text (text/plain)") }
            )
        }

        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        val now = Clock.System.now()
                        val shareManager = ShareManager()
                        shareManager.shareDocument(
                            content = Res.readBytes("files/utopia-brewery.png"),
                            filename = "test-image.png",
                            mimeType = "image/png",
                            title = "Test sharing of image/png"
                        )
                    }
                },
                content = { Text("Share Utopia Brewery image (image/png)") }
            )
        }
    }
}
