package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.document.DocumentModel
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.testapp.rememberInhibitNfcObserveMode

@Composable
fun DocumentViewerScreen(
    documentModel: DocumentModel,
    documentStore: DocumentStore,
    documentId: String,
    showToast: (message: String) -> Unit,
    onViewCredential: (documentId: String, credentialId: String) -> Unit,
    onProvisionMore: (document: Document, authorizationData: ByteString) -> Unit,
    onDocumentDeleted: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val documentInfos = documentModel.documentInfos.collectAsState().value
    val documentInfo = documentInfos.find { it.document.identifier == documentId }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 10.dp),
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
                        KeyValuePairText(
                            modifier = Modifier
                                .padding(start = 24.dp)
                                .clickable {
                                    onViewCredential(
                                        documentInfo.document.identifier,
                                        credentialInfo.credential.identifier
                                    )
                                },
                            keyText = credentialInfo.credential.credentialType,
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
                        Text("Provision more credentials")
                    }
                }
            }

        }
    }
}
