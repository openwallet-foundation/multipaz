package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import org.multipaz.credential.Credential
import org.multipaz.revocation.RevocationCheckState
import org.multipaz.revocation.RevocationChecker
import org.multipaz.revocation.RevocationInfo
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.getRevocationInfo
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.util.toHex

@Composable
fun RevocationStatusSection(
    revocationChecker: RevocationChecker,
    issuerTrustManager: TrustManagerInterface,
    credential: Credential
) {
    val coroutineScope = rememberCoroutineScope()
    val revocationData = remember { mutableStateOf<RevocationInfo?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            revocationData.value = credential.getRevocationInfo(issuerTrustManager)
        }
    }
    val value = revocationData.value
    if (value != null) {
        RevocationCheckSection(revocationChecker, value)
    } else {
        Text(
            text = "Revocation Info Not Found",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun RevocationCheckSection(
    revocationChecker: RevocationChecker,
    revocationData: RevocationInfo
) {
    val coroutineScope = rememberCoroutineScope()
    val statusText = remember { mutableStateOf("Click to check status") }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable {
                coroutineScope.launch {
                    val result = revocationChecker.check(
                        revocationStatus = revocationData.revocationStatus,
                        issuerCert = revocationData.certificate,
                        onlyTrusted = false  // Only for use in testing!
                    )
                    val state = when (result.state) {
                        RevocationCheckState.VALID -> "Valid"
                        RevocationCheckState.INVALID -> "Invalid"
                        RevocationCheckState.SUSPENDED -> "Suspended"
                        RevocationCheckState.UNKNOWN -> "Unknown"
                    }
                    val trust = if (result.isTrusted) "Trusted" else "Not trusted"
                    statusText.value = if (result.error == null) {
                        "$state ($trust)"
                    } else {
                        "$state ($trust) [${result.error!!::class.simpleName}]"
                    }
                }
            }
    ) {
        Text(
            text = "Status List Revocation",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        when (val status = revocationData.revocationStatus) {
            is RevocationStatus.StatusList -> {
                Text(
                    text = "Index: ${status.idx}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Url: ${status.uri}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            is RevocationStatus.IdentifierList -> {
                Text(
                    text = "Identifier: ${status.id.toByteArray().toHex()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Url: ${status.uri}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            else -> Text("Unknown revocation data format")
        }
        Text(
            text = statusText.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
