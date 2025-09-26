package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.multipaz.nfc.Nfc
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlinx.coroutines.launch
import org.multipaz.nfc.NfcTagReader
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcScreen"

@Composable
fun NfcScreen(
    externalNfcTagReaders: List<NfcTagReader>,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val showCustomNfcDialog = remember { mutableStateOf(false) }
    val dismissableNfcJob = remember { mutableStateOf<Job?>(null) }

    if (showCustomNfcDialog.value) {
        fun onDismissRequest() {
            println("TODO: stop NFC scanning")
            showCustomNfcDialog.value = false
            dismissableNfcJob.value?.cancel()
            dismissableNfcJob.value = null
        }
        AlertDialog(
            modifier = Modifier,
            title = { Text(text = "Custom NFC scanning") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = "This is a custom NFC scanning dialog.")
                    Text(text = "Its only purpose is to demonstrate that NFC scanning works " +
                            "without showing a dialog on some platforms, including Android. Scanning " +
                            "will stop when this dialog is closed. " +
                            "Scanning an NDEF tag will also dismiss the dialog")
                }
            },
            onDismissRequest = ::onDismissRequest,
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onDismissRequest() }) {
                    Text("Close")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val reader = if (externalNfcTagReaders.size > 0)  {
                                externalNfcTagReaders.first()
                            } else {
                                NfcTagReader.getReaders().first()
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
                            val reader = NfcTagReader.getReaders().first()
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
                            val reader = NfcTagReader.getReaders().first()
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
                            showCustomNfcDialog.value = false
                        }
                    }
                    showCustomNfcDialog.value = true
                },
                content = { Text("Scan for NDEF tag and read CC file (invisible prompt)") }
            )
        }
    }
}

