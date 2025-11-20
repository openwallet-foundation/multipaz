package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import org.multipaz.crypto.EcPublicKey
import org.multipaz.statuslist.StatusList
import org.multipaz.testapp.platformHttpClientEngineFactory

@Composable
fun StatusCheckSection(
    index: Int,
    url: String,
    publicKey: EcPublicKey
) {
    val coroutineScope = rememberCoroutineScope()
    val statusText = remember { mutableStateOf("Click to check status") }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable {
                coroutineScope.launch {
                    val client = HttpClient(platformHttpClientEngineFactory())
                    val response = client.get(url)
                    if (response.status != HttpStatusCode.OK) {
                        statusText.value = "HTTP Status: ${response.status}"
                    } else {
                        try {
                            val statusList = StatusList.fromJwt(
                                jwt = response.readBytes().decodeToString(),
                                publicKey = publicKey
                            )
                            statusText.value = when (val code = statusList[index]) {
                                0 -> "Valid"
                                1 -> "Invalid"
                                2 -> "Suspended"
                                else -> "Unexpected status $code"
                            }
                        } catch (_: Exception) {
                            statusText.value = "Failed to parse status list"
                        }
                    }
                }
            }
    ) {
        Text(
            text = "Status List",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Index: $index",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "Url: $url",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = statusText.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
