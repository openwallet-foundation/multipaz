package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.multipaz.compose.items.FloatingItemCenteredText
import org.multipaz.compose.pickers.FilePicker
import org.multipaz.compose.pickers.rememberFilePicker
import org.multipaz.compose.trustmanagement.TrustEntryInfo
import org.multipaz.compose.trustmanagement.TrustEntryList
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.TrustEntryAlreadyExistsException

@Composable
private fun FloatingActionButtonMenu(
    importCertificateFilePicker: FilePicker,
    importVicalFilePicker: FilePicker?,
    importRicalFilePicker: FilePicker?,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(horizontalAlignment = Alignment.End) {
            AnimatedVisibility(
                visible = isMenuExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    ExtendedFloatingActionButton(
                        text = { Text("Import Certificate") },
                        onClick = {
                            isMenuExpanded = false
                            importCertificateFilePicker.launch()
                        },
                        icon = { Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null
                        ) },
                        elevation = FloatingActionButtonDefaults.elevation(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (importVicalFilePicker != null) {
                        ExtendedFloatingActionButton(
                            text = { Text("Import VICAL") },
                            onClick = {
                                isMenuExpanded = false
                                importVicalFilePicker.launch()
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = null
                                )
                            },
                            elevation = FloatingActionButtonDefaults.elevation(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (importRicalFilePicker != null) {
                        ExtendedFloatingActionButton(
                            text = { Text("Import RICAL") },
                            onClick = {
                                isMenuExpanded = false
                                importRicalFilePicker.launch()
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = null
                                )
                            },
                            elevation = FloatingActionButtonDefaults.elevation(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { isMenuExpanded = !isMenuExpanded },
                elevation = FloatingActionButtonDefaults.elevation(8.dp),
                content = {
                    Icon(
                        imageVector = if (isMenuExpanded) Icons.Filled.Menu else Icons.Filled.Add,
                        contentDescription = null,
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustManagerScreen(
    builtIn: TrustManagerModel,
    user: TrustManagerModel,
    isVical: Boolean,
    imageLoader: ImageLoader,
    onTrustEntryClicked: (trustEntryInfo: TrustEntryInfo) -> Unit,
    onTrustEntryAdded: (trustEntryInfo: TrustEntryInfo) -> Unit,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val showImportErrorDialog = remember { mutableStateOf<String?>(null) }
    val importCertificateFilePicker = rememberFilePicker(
        types = listOf(
            "application/x-pem-file",
            "application/x-x509-key; format=pem",
            "application/x-x509-cert; format=pem",
            "application/x-x509-ca-cert",
            "application/x-x509-ca-cert; format=der",
            "application/pkix-cert",
            "application/pkix-crl",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val cert = X509Cert.fromPem(pemEncoding = files[0].toByteArray().decodeToString())
                        val entry = user.trustManager.addX509Cert(
                            certificate = cert,
                            metadata = TrustMetadata()
                        )
                        val updatedInfos = user.trustManagerInfos.first { infos ->
                            infos.any { it.entry.identifier == entry.identifier }
                        }
                        onTrustEntryAdded(
                            updatedInfos.find { it.entry.identifier == entry.identifier }!!
                        )
                    } catch (_: TrustEntryAlreadyExistsException) {
                        showImportErrorDialog.value = "A certificate with this Subject Key Identifier already exists"
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        e.printStackTrace()
                        showImportErrorDialog.value = "Importing certificate failed: $e"
                    }
                }
            }
        }
    )

    val importVicalFilePicker = rememberFilePicker(
        // Unfortunately there's no well-defined MIME type for a VICAL.
        types = listOf(
            "*/*",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val encodedSignedVical = files[0]
                        // Parse it once, to check the signature is good
                        val signedVical = SignedVical.parse(
                            encodedSignedVical = encodedSignedVical.toByteArray(),
                            disableSignatureVerification = false
                        )
                        val entry = user.trustManager.addVical(
                            encodedSignedVical = encodedSignedVical,
                            metadata = TrustMetadata()
                        )
                        val updatedInfos = user.trustManagerInfos.first { infos ->
                            infos.any { it.entry.identifier == entry.identifier }
                        }
                        onTrustEntryAdded(
                            updatedInfos.find { it.entry.identifier == entry.identifier }!!
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        e.printStackTrace()
                        showImportErrorDialog.value = "Importing VICAL failed: $e"
                    }
                }
            }
        }
    )

    val importRicalFilePicker = rememberFilePicker(
        // Unfortunately there's no well-defined MIME type for a VICAL.
        types = listOf(
            "*/*",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val encodedSignedRical = files[0]
                        // Parse it once, to check the signature is good
                        val signedRical = SignedRical.parse(
                            encodedSignedRical = encodedSignedRical.toByteArray(),
                            disableSignatureVerification = false
                        )
                        val entry = user.trustManager.addRical(
                            encodedSignedRical = encodedSignedRical,
                            metadata = TrustMetadata()
                        )
                        val updatedInfos = user.trustManagerInfos.first { infos ->
                            infos.any { it.entry.identifier == entry.identifier }
                        }
                        onTrustEntryAdded(
                            updatedInfos.find { it.entry.identifier == entry.identifier }!!
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        e.printStackTrace()
                        showImportErrorDialog.value = "Importing RICAL failed: $e"
                    }
                }
            }
        }
    )

    showImportErrorDialog.value?.let {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog.value = null },
            confirmButton = {
                TextButton(
                    onClick = { showImportErrorDialog.value = null }
                ) {
                    Text(text = "Close")
                }
            },
            title = {
                Text(text = "Error importing")
            },
            text = {
                Text(text = it)
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButtonMenu(
                importCertificateFilePicker = importCertificateFilePicker,
                importVicalFilePicker = if (isVical) importVicalFilePicker else null,
                importRicalFilePicker = if (!isVical) importRicalFilePicker else null
            )
        },
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .fillMaxSize()
                .padding(8.dp),
        ) {
            TrustEntryList(
                trustManagerModel = builtIn,
                title = "Built-in",
                imageLoader = imageLoader,
                noItems = {
                    Text(
                        text = "No built-in trust points",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center
                    )
                },
                onTrustEntryClicked = { trustEntryInfo ->
                    onTrustEntryClicked(trustEntryInfo)
                }
            )
            TrustEntryList(
                trustManagerModel = user,
                title = "Manually imported",
                imageLoader = imageLoader,
                noItems = {
                    FloatingItemCenteredText(
                        text = "Certificates and trust lists manually imported will appear in this list",
                    )
                },
                onTrustEntryClicked = { trustEntryInfo ->
                    onTrustEntryClicked(trustEntryInfo)
                }
            )
        }
    }
}
