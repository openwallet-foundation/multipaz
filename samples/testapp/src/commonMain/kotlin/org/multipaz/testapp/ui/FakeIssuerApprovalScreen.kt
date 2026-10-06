package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.decodeImage

/**
 * Screen asking the user for approval to send the verified captured portrait to a fake credential issuer.
 *
 * @param portraitBytes the captured portrait image bytes.
 * @param onApprove invoked when the user approves sending the portrait to the issuer.
 * @param onCancel invoked when the user cancels or rejects sending the portrait.
 */
@Composable
fun FakeIssuerApprovalScreen(
    portraitBytes: ByteString?,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    val imageBitmap: ImageBitmap? = remember(portraitBytes) {
        if (portraitBytes != null) {
            try {
                decodeImage(portraitBytes.toByteArray())
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Liveness Verified",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }
        }

        item {
            if (imageBitmap != null) {
                val cornerRadius = 20.dp
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Captured Portrait",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 180.dp, height = 230.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(cornerRadius)
                        )
                )
            } else {
                Card(
                    modifier = Modifier
                        .size(width = 180.dp, height = 230.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No Portrait Available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Credential Provisioning Request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    IssuerDetailRow(label = "Issuer", value = "Utopia Department of Motor Vehicles")
                    IssuerDetailRow(label = "Credential", value = "Mobile Driving Licence (mDL)")
                    IssuerDetailRow(label = "Biometric Status", value = "Active Liveness Verified")
                    if (portraitBytes != null) {
                        IssuerDetailRow(label = "Portrait Size", value = "${portraitBytes.size / 1024} KB")
                    }
                }
            }
        }

        item {
            Text(
                text = "The issuer requires this portrait photo to issue your digital credential. " +
                    "Do you approve sending this portrait to the fake issuer?",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onApprove,
                    enabled = portraitBytes != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Approve and Send to Issuer")
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun IssuerDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
