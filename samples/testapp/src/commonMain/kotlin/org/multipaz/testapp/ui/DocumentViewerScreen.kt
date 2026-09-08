package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.document.DocumentModel
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.testapp.rememberInhibitNfcObserveMode
import org.multipaz.util.fromHexByteString
import org.multipaz.util.toHex

private data class KnownReader(
    val name: String,
    val akiHex: String,
)

private val KNOWN_READERS = listOf(
    KnownReader("verifier.multipaz.org", "b18439852f4a6eeabfea62adbc51d081f7488729"),
    KnownReader("ws.davidz25.net", "d53943bafe90446382c7256c895e0a7d44f4caf3"),
    KnownReader("Multipaz Wallet", "cfa4af87907312962e4d7a17646acc1c45719b21"),
    KnownReader("Multipaz Wallet Dev", "9bcfdafd2059978e21869c7dd28aaf7481ebabc5"),
)

@Composable
fun DocumentViewerScreen(
    documentModel: DocumentModel,
    documentStore: DocumentStore,
    documentId: String,
    showToast: (message: String) -> Unit,
    onViewCredential: (documentId: String, credentialId: String) -> Unit,
    onProvisionMore: (document: Document, authorizationData: ByteString) -> Unit,
    onDeleteAllCredentials: (document: Document) -> Unit,
    onDocumentDeleted: () -> Unit,
    onOpenInVerticalCardList: (documentId: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val documentInfos = documentModel.documentInfos.collectAsState().value
    val documentInfo = documentInfos.find { it.document.identifier == documentId }
    var showEditReaderIdentifiersDialog by remember { mutableStateOf(false) }

    rememberInhibitNfcObserveMode()

    Column(Modifier.padding(8.dp)) {
        if (documentInfo == null) {
            Text("No document for identifier ${documentId}")
        } else {
            val document = documentInfo.document
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    modifier = Modifier.height(200.dp),
                    contentScale = ContentScale.FillHeight,
                    bitmap = documentInfo.cardArt,
                    contentDescription = null,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    modifier = Modifier.padding(8.dp),
                    text = document.typeDisplayName ?: "(typeDisplayName not set)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KeyValuePairText(
                    keyText = "Provisioned",
                    valueText = if (document.provisioned) "Yes" else "No"
                )
                KeyValuePairText(
                    keyText = "Document Type",
                    valueText = document.typeDisplayName ?: "(typeDisplayName not set)"
                )
                KeyValuePairText(
                    keyText = "Document Name",
                    valueText = document.displayName ?: "(displayName not set)"
                )
                KeyValuePairText(
                    keyText = "Reader Identifiers",
                    valueText = if (document.readerIdentifiers.isEmpty()) {
                        "None (accessible to all readers)"
                    } else {
                        document.readerIdentifiers.joinToString("\n") { aki ->
                            val hex = aki.toHex()
                            val known = KNOWN_READERS.find { it.akiHex.equals(hex, ignoreCase = true) }
                            if (known != null) {
                                "${known.name} ($hex)"
                            } else {
                                hex
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1.0f),
                        onClick = {
                            showEditReaderIdentifiersDialog = true
                        },
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 8.dp),
                            text = "Configure Reader Identifiers"
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1.0f),
                        onClick = {
                            onOpenInVerticalCardList(documentId)
                        },
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 8.dp),
                            text = "Open in Vertical Card List"
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1.0f),
                        onClick = {
                            coroutineScope.launch {
                                documentStore.deleteDocument(documentId)
                            }
                            onDocumentDeleted()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 8.dp),
                            text = "Delete document"
                        )
                    }
                }
                Text(
                    text = "Credentials",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                val domains = mutableSetOf<String>()
                for (credentialInfo in documentInfo.credentialInfos) {
                    domains.add(credentialInfo.credential.domain)
                }
                for (domain in domains.sorted()) {
                    Text(
                        modifier = Modifier.padding(start = 16.dp),
                        text = "$domain domain",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic
                    )
                    for (credentialInfo in documentInfo.credentialInfos) {
                        if (credentialInfo.credential.domain != domain) {
                            continue
                        }
                        val keyText = if (credentialInfo.credential.isCertified) {
                            credentialInfo.credential.credentialType
                        } else {
                            "${credentialInfo.credential.credentialType} (Pending)"
                        }
                        KeyValuePairText(
                            modifier = Modifier
                                .padding(start = 24.dp)
                                .clickable {
                                    onViewCredential(
                                        documentInfo.document.identifier,
                                        credentialInfo.credential.identifier
                                    )
                                },
                            keyText = keyText,
                            valueText = buildAnnotatedString {
                                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                                    append("Usage count ${credentialInfo.credential.usageCount}. Click for details")
                                }
                            }
                        )
                    }
                }
                document.authorizationData?.let { authorizationData ->
                    Button(onClick = {
                        onProvisionMore(documentInfo.document, authorizationData)
                    }) {
                        Text("Refresh credentials")
                    }
                    Button(
                        onClick = {
                            onDeleteAllCredentials(documentInfo.document)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Delete all credentials")
                    }
                }
            }

            if (showEditReaderIdentifiersDialog) {
                EditReaderIdentifiersDialog(
                    initialIdentifiers = document.readerIdentifiers,
                    onDismiss = { showEditReaderIdentifiersDialog = false },
                    onSave = { newIdentifiers ->
                        coroutineScope.launch {
                            document.edit {
                                readerIdentifiers = newIdentifiers
                            }
                            showToast("Reader Identifiers updated")
                        }
                        showEditReaderIdentifiersDialog = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EditReaderIdentifiersDialog(
    initialIdentifiers: List<ByteString>,
    onDismiss: () -> Unit,
    onSave: (List<ByteString>) -> Unit,
) {
    val currentIdentifiers = remember { mutableStateListOf<ByteString>().apply { addAll(initialIdentifiers) } }
    var customAkiText by remember { mutableStateOf("") }
    var customAkiError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Configure Reader Identifiers")
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "If configured, only readers using reader authentication with a certificate matching one of these Authority Key Identifiers (AKIs) will be allowed access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Configured Readers (${currentIdentifiers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (currentIdentifiers.isEmpty()) {
                    Text(
                        text = "None (accessible to all readers)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontStyle = FontStyle.Italic
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        currentIdentifiers.toList().forEach { aki ->
                            val hex = aki.toHex()
                            val known = KNOWN_READERS.find { it.akiHex.equals(hex, ignoreCase = true) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (known != null) {
                                        Text(
                                            text = known.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        text = hex,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(
                                    onClick = { currentIdentifiers.remove(aki) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Quick Add Known Readers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KNOWN_READERS.forEach { known ->
                        val isAdded = currentIdentifiers.any { it.toHex().equals(known.akiHex, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAdded) {
                                    currentIdentifiers.add(known.akiHex.fromHexByteString())
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = known.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isAdded) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                    fontWeight = if (isAdded) FontWeight.Normal else FontWeight.Medium
                                )
                                Text(
                                    text = known.akiHex,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (isAdded) {
                                Text(
                                    text = "Added",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                Text(
                                    text = "+ Add",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Add Custom Reader AKI (Hex)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = customAkiText,
                        onValueChange = {
                            customAkiText = it
                            customAkiError = null
                        },
                        label = { Text("AKI Hex") },
                        placeholder = { Text("e.g. b1843985...") },
                        isError = customAkiError != null,
                        supportingText = customAkiError?.let { { Text(it) } },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val trimmed = customAkiText.trim()
                            if (trimmed.isEmpty()) {
                                customAkiError = "Cannot be empty"
                                return@Button
                            }
                            try {
                                val bs = trimmed.fromHexByteString()
                                if (currentIdentifiers.any { it == bs }) {
                                    customAkiError = "Already added"
                                } else {
                                    currentIdentifiers.add(bs)
                                    customAkiText = ""
                                    customAkiError = null
                                }
                            } catch (e: Exception) {
                                customAkiError = "Invalid hex string"
                            }
                        }
                    ) {
                        Text("Add")
                    }
                }

                if (currentIdentifiers.isNotEmpty()) {
                    TextButton(
                        onClick = { currentIdentifiers.clear() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Clear All")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(currentIdentifiers.toList())
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
