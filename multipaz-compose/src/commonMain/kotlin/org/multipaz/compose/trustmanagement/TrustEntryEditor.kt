package org.multipaz.compose.trustmanagement

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.cropRotateScaleImage
import org.multipaz.compose.decodeImage
import org.multipaz.compose.encodeImageToPng
import org.multipaz.compose.pickers.rememberImagePicker
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_change
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_name_label
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_remove
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_test_only_rical
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_test_only_vical
import org.multipaz.multipaz_compose.generated.resources.trust_entry_editor_test_only_x509
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.math.min

/**
 * A Composable that provides a UI for editing the mutable [TrustMetadata] associated
 * with a specific trust entry.
 *
 * It allows the user to override the display name, toggle the "test only" flag,
 * and select a custom display icon from the device's image picker. Modified metadata
 * is immediately reflected in the [newMetadata] state.
 *
 * @param trustEntryInfo The trust entry currently being edited.
 * @param imageLoader a [ImageLoader].
 * @param newMetadata A mutable state holding the updated [TrustMetadata] object.
 * Modifications made in this editor are written directly to this state.
 */
@Composable
fun TrustEntryEditor(
    trustEntryInfo: TrustEntryInfo,
    imageLoader: ImageLoader,
    newMetadata: MutableState<TrustMetadata>
) {
    var nameText by remember { mutableStateOf(
        TextFieldValue(trustEntryInfo.entry.metadata.displayName ?: "")
    )}
    val fallbackName = trustEntryInfo.entry.getFallbackName(trustEntryInfo.signedVical, trustEntryInfo.signedRical)

    val imagePicker = rememberImagePicker(
        allowMultiple = false,
        onResult = { payloads ->
            if (payloads.isNotEmpty()) {
                // Resize to 256x256 and crop so it fits.
                val image = decodeImage(payloads[0].toByteArray())
                val imageSize = min(image.width, image.height)
                val croppedImage = image.cropRotateScaleImage(
                    cx = image.width.toDouble() / 2,
                    cy = image.height.toDouble() / 2,
                    angleDegrees = 0.0,
                    outputWidthPx = imageSize,
                    outputHeightPx = imageSize,
                    targetWidthPx = 256
                )
                val encodedCroppedImage = encodeImageToPng(croppedImage)
                newMetadata.value = newMetadata.value.copy(
                    displayIcon = encodedCroppedImage,
                )
            }
        }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        newMetadata.value.displayIcon.let {
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                if (it == null) {
                    trustEntryInfo.RenderImage(
                        size = 160.dp,
                        imageLoader = imageLoader
                    )
                } else {
                    Image(
                        bitmap = decodeImage(it.toByteArray()),
                        contentDescription = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                TextButton(
                    onClick = { imagePicker.launch() }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.trust_entry_editor_change))
                }
                TextButton(
                    enabled = newMetadata.value.displayIcon != null,
                    onClick = {
                        newMetadata.value = newMetadata.value.copy(
                            displayIcon = null,
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.trust_entry_editor_remove))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            OutlinedTextField(
                value = nameText,
                label = { Text(text = stringResource(Res.string.trust_entry_editor_name_label)) },
                placeholder = {
                    Text(text = fallbackName)
                },
                onValueChange = {
                    nameText = it
                    newMetadata.value = newMetadata.value.copy(
                        displayName = it.text.ifEmpty { null },
                    )
                },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = newMetadata.value.testOnly,
                    onCheckedChange = {
                        newMetadata.value = newMetadata.value.copy(
                            testOnly = it
                        )
                    }
                )
                Text(
                    text = when (trustEntryInfo.entry) {
                        is TrustEntryX509Cert -> stringResource(Res.string.trust_entry_editor_test_only_x509)
                        is TrustEntryVical -> stringResource(Res.string.trust_entry_editor_test_only_vical)
                        is TrustEntryRical -> stringResource(Res.string.trust_entry_editor_test_only_rical)
                    }
                )
            }
        }
    }
}