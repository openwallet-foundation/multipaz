package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import org.multipaz.compose.trustmanagement.TrustEntryEditor
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustEntryEditScreen(
    trustManagerModel: TrustManagerModel,
    trustEntryId: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var showConfirmationBeforeExiting by remember { mutableStateOf(false) }

    val info = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == trustEntryId
    } ?: return
    val newMetadata = remember { mutableStateOf<TrustMetadata>(info.entry.metadata) }

    if (showConfirmationBeforeExiting) {
        AlertDialog(
            onDismissRequest = { showConfirmationBeforeExiting = false },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmationBeforeExiting = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            showConfirmationBeforeExiting = false
                            onBack()
                        }
                    }
                ) {
                    Text(text = "Discard changes")
                }
            },
            title = {
                Text(text = "Discard unsaved changes?")
            },
            text = {
                Text(text = "You have unsaved changes that will be lost if you leave this page")
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (info.entry) {
                            is TrustEntryX509Cert -> "Edit IACA certificate"
                            is TrustEntryVical -> "Edit VICAL"
                            is TrustEntryRical -> "Edit RICAL"
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (newMetadata.value != info.entry.metadata) {
                            showConfirmationBeforeExiting = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = (newMetadata.value != info.entry.metadata),
                        onClick = {
                            coroutineScope.launch {
                                trustManagerModel.trustManager.updateMetadata(
                                    entry = info.entry,
                                    metadata = newMetadata.value
                                )
                                onBack()
                            }
                        }
                    ) {
                        Text(text = "Save")
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
            TrustEntryEditor(
                trustEntryInfo = info,
                imageLoader = imageLoader,
                newMetadata = newMetadata
            )
        }
    }
}
