package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.multipaz.compose.trustmanagement.TrustEntryViewer
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.testapp.App
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManager

@OptIn(ExperimentalResourceApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrustEntryScreen(
    trustManagerModel: TrustManagerModel,
    trustEntryId: String,
    canEditOrDelete: Boolean,
    justImported: Boolean,
    imageLoader: ImageLoader,
    onViewVicalEntry: (vicalCertNum: Int) -> Unit,
    onViewRicalEntry: (ricalCertNum: Int) -> Unit,
    onViewCertificate: (certificate: X509Cert) -> Unit,
    onViewCertificateChain: (certificateChain: X509CertChain) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

    val info = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == trustEntryId
    } ?: return

    if (showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            showDeleteConfirmationDialog = false
                            trustManagerModel.trustManager.deleteEntry(info.entry)
                            onBack()
                        }
                    }
                ) {
                    Text(text = "Delete")
                }
            },
            title = {
                Text(
                    text = when (info.entry) {
                        is TrustEntryX509Cert -> "Delete certificate?"
                        is TrustEntryVical -> "Delete VICAL?"
                        is TrustEntryRical -> "Delete RICAL?"
                    }
                )
            },
            text = {
                Text(
                    text = when (info.entry) {
                        is TrustEntryX509Cert -> "The certificate will be permanently deleted. This action cannot be undone"
                        is TrustEntryVical -> "The VICAL will be permanently deleted. This action cannot be undone"
                        is TrustEntryRical -> "The RICAL will be permanently deleted. This action cannot be undone"
                    }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (info.entry) {
                            is TrustEntryX509Cert -> "View Certificate"
                            is TrustEntryVical -> "View VICAL"
                            is TrustEntryRical -> "View RICAL"
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (canEditOrDelete) {
                        IconButton(
                            onClick = { onEdit() }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null
                            )
                        }
                        IconButton(
                            onClick = { showDeleteConfirmationDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            TrustEntryViewer(
                trustManagerModel = trustManagerModel,
                trustEntryId = trustEntryId,
                justImported = justImported,
                imageLoader = imageLoader,
                onViewVicalEntry = onViewVicalEntry,
                onViewRicalEntry = onViewRicalEntry,
                onViewCertificate = onViewCertificate,
                onViewCertificateChain = onViewCertificateChain
            )
        }
    }
}