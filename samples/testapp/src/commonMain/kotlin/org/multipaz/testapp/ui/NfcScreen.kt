package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.multipaz.nfc.Nfc
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlinx.coroutines.launch
import org.multipaz.nfc.ExternalNfcReader
import org.multipaz.nfc.ExternalNfcReaderState
import org.multipaz.nfc.ExternalNfcReaderStore
import org.multipaz.nfc.NfcTagReader
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcScreen"

internal sealed class NfcReaderEntry(
    open val displayName: String,
) {
    abstract suspend fun getNfcTagReader(): NfcTagReader
}

internal data class NfcReaderInternal(
    override val displayName: String,
    val index: Int
): NfcReaderEntry(displayName) {
    override suspend fun getNfcTagReader(): NfcTagReader = NfcTagReader.getReaders()[index]
}

internal data class NfcReaderExternal(
    override val displayName: String,
    val externalReader: ExternalNfcReader
): NfcReaderEntry(displayName) {

    override suspend fun getNfcTagReader(): NfcTagReader {
        when (externalReader.observeState().first()) {
            ExternalNfcReaderState.NOT_CONNECTED -> {
                throw IllegalStateException("Reader is not connected")
            }
            ExternalNfcReaderState.CONNECTED_NO_PERMISSION -> {
                if (!externalReader.requestPermission()) {
                    throw IllegalStateException("Permission not granted")
                }
                return externalReader.getNfcTagReader()
            }
            ExternalNfcReaderState.CONNECTED -> {
                return externalReader.getNfcTagReader()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcScreen(
    externalNfcReaderStore: ExternalNfcReaderStore,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val dismissableNfcJob = remember { mutableStateOf<Job?>(null) }
    var externalReaderPromptMessage by remember { mutableStateOf<String?>(null) }

    val readers = mutableListOf<NfcReaderEntry>()
    NfcTagReader.getReaders().forEachIndexed { index, reader ->
        readers.add(NfcReaderInternal("Internal NFC Reader", index))
    }
    externalNfcReaderStore.readers.value.forEach { externalReader ->
        readers.add(NfcReaderExternal(externalReader.displayName, externalReader))
    }
    val readerSelected = remember { mutableStateOf<NfcReaderEntry>(
        if (lastNfcReaderSelected < readers.size) readers[lastNfcReaderSelected] else readers[0]
    ) }
    val readerDropdownExpanded = remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    if (externalReaderPromptMessage != null) {
        fun onCancel() {
            dismissableNfcJob.value?.cancel()
        }
        ModalBottomSheet(
            onDismissRequest = { onCancel() },
            sheetState = sheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Prompt not shown for NFC scanning",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(text = "This happens for external NFC readers or if the OS doesn't draw a dialog.")
                Text(text = "Message: $externalReaderPromptMessage")
                TextButton(onClick = { onCancel() }) {
                    Text("Cancel")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            ComboBox(
                headline = "NFC Reader",
                options = readers,
                comboBoxSelected = readerSelected,
                comboBoxExpanded = readerDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastNfcReaderSelected = index }
            )
        }

        item {
            TextButton(
                onClick = {
                    dismissableNfcJob.value = coroutineScope.launch {
                        try {
                            val reader = readerSelected.value.getNfcTagReader()
                            if (reader.dialogNeverShown) {
                                externalReaderPromptMessage = "Hold your phone near a NDEF tag."
                            }
                            val ccFile = reader.scan(
                                message = "Hold your phone near a NDEF tag.",
                                tagInteractionFunc = { tag ->
                                    tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                                    tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
                                    val ccFile = tag.readBinary(0, 15)
                                    check(ccFile.size == 15) { "CC file is ${ccFile.size} bytes, expected 15" }
                                    tag.updateDialogMessage("CC file: ${ccFile.toHex()}")
                                    tag.close()
                                    ccFile
                                }
                            )
                            showToast("NDEF CC file: ${ccFile.toHex()}")
                        } catch (e: PromptDismissedException) {
                            showToast("Dialog dismissed by user")
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Fail", e)
                            e.printStackTrace()
                            showToast("Fail: ${e.message}")
                        } finally {
                            externalReaderPromptMessage = null
                            dismissableNfcJob.value = null
                        }
                    }
                },
                content = { Text("Scan for NDEF tag and read CC file") }
            )
        }

        item {
            TextButton(
                onClick = {
                    dismissableNfcJob.value = coroutineScope.launch {
                        try {
                            val reader = readerSelected.value.getNfcTagReader()
                            if (reader.dialogNeverShown) {
                                externalReaderPromptMessage = "Hold your phone near a NDEF tag."
                            }
                            val ccFile = reader.scan(
                                message = "Hold your phone near a NDEF tag.",
                                tagInteractionFunc = { tag ->
                                    tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                                    tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
                                    val ccFile = tag.readBinary(0, 15)
                                    check(ccFile.size == 15) { "CC file is ${ccFile.size} bytes, expected 15" }
                                    tag.updateDialogMessage("CC file: ${ccFile.toHex()}")
                                    tag.close()
                                    ccFile
                                },
                            )
                            showToast("NDEF CC file: ${ccFile.toHex()}")
                        } catch (e: PromptDismissedException) {
                            showToast("Dialog dismissed by user")
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Fail", e)
                            e.printStackTrace()
                            showToast("Fail: ${e.message}")
                        } finally {
                            externalReaderPromptMessage = null
                            dismissableNfcJob.value = null
                        }
                    }
                    coroutineScope.launch {
                        delay(5.seconds)
                        dismissableNfcJob.value?.cancel()
                        dismissableNfcJob.value = null
                    }
                },
                content = { Text("Scan for NDEF tag and read CC file (dismiss after 5 sec)") }
            )
        }

        item {
            TextButton(
                onClick = {
                    dismissableNfcJob.value = coroutineScope.launch {
                        try {
                            val reader = readerSelected.value.getNfcTagReader()
                            externalReaderPromptMessage = "Hold your phone near a NDEF tag."
                            val ccFile = reader.scan(
                                message = null,
                                tagInteractionFunc = { tag ->
                                    tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                                    tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
                                    val ccFile = tag.readBinary(0, 15)
                                    check(ccFile.size == 15) { "CC file is ${ccFile.size} bytes, expected 15" }
                                    tag.updateDialogMessage("CC file: ${ccFile.toHex()}")
                                    tag.close()
                                    ccFile
                                },
                            )
                            showToast("NDEF CC file: ${ccFile.toHex()}")
                        } catch (e: PromptDismissedException) {
                            showToast("Dialog dismissed by user")
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Fail", e)
                            e.printStackTrace()
                            showToast("Fail: ${e.message}")
                        } finally {
                            externalReaderPromptMessage = null
                            dismissableNfcJob.value = null
                        }
                    }
                },
                content = { Text("Scan for NDEF tag and read CC file (invisible prompt)") }
            )
        }
    }
}

