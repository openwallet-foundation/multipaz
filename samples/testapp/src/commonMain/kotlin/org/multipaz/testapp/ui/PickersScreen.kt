package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.decodeImage
import org.multipaz.compose.pickers.rememberFilePicker
import org.multipaz.compose.pickers.rememberImagePicker

@Composable
fun PickersScreen() {
    var importedTextFiles by remember{ mutableStateOf(listOf<Pair<String, ByteArray>>())}
    var importedImageFiles by remember{ mutableStateOf(listOf<Pair<String, ByteArray>>())}
    var importedImages by remember{ mutableStateOf(listOf<ByteString>())}

    val textFilePicker = rememberFilePicker(
        types = listOf("text/*"),
        allowMultiple = false,
        onResult = {files ->
            if(files.isNotEmpty()){
                val fileBytes = files.first().toByteArray()
                val displayName = "Text File ${importedTextFiles.size + 1}"
                importedTextFiles = importedTextFiles + (displayName to fileBytes)
            }

        }
    )

    val textFileMultiplePicker = rememberFilePicker(
        types = listOf("text/*"),
        allowMultiple = true,
        onResult = {files ->
            if(files.isNotEmpty()){
                val fileBytes = files.first().toByteArray()
                val displayName = "Text File ${importedTextFiles.size + 1}"
                importedTextFiles = importedTextFiles + (displayName to fileBytes)
            }

        }
    )

    val imageFilePicker = rememberFilePicker(
        types = listOf("image/*"),
        allowMultiple = false,
        onResult = {files ->
            if(files.isNotEmpty()){
                val fileBytes = files.first().toByteArray()
                val displayName = "Image File ${importedImageFiles.size + 1}"
                importedImageFiles = importedImageFiles + (displayName to fileBytes)
            }

        }
    )

    val imageFileMultiplePicker = rememberFilePicker(
        types = listOf("image/*"),
        allowMultiple = true,
        onResult = {files ->
            if(files.isNotEmpty()){
                val fileBytes = files.first().toByteArray()
                val displayName = "Image File ${importedImageFiles.size + 1}"
                importedImageFiles = importedImageFiles + (displayName to fileBytes)
            }

        }
    )

    val imagePicker = rememberImagePicker(
        allowMultiple = false,
        onResult = { files ->
            if(files.isNotEmpty()){
                importedImages = importedImages + files
            }
        }
    )

    val imageMultiplePicker = rememberImagePicker(
        allowMultiple = true,
        onResult = { files ->
            if(files.isNotEmpty()){
                importedImages = importedImages + files
            }
        }
    )

    LazyColumnWithScrollbar(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        stickyHeader {
            Text(
                text = ("Test File and Image Pickers. Select Button below to add text files, " +
                        "image files and images"),
                style = MaterialTheme.typography.titleMedium.copy(
                    MaterialTheme.colorScheme.primary
                )
            )

            Spacer(Modifier.height(10.dp))
        }
        item{
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ){
                Button(
                    onClick = { textFilePicker.launch() },
                ){
                    ButtonText("Single Text File")
                }

                Button(
                    onClick = { textFileMultiplePicker.launch() },
                ){
                    ButtonText("Multiple Text File")
                }
            }

        }

        itemsIndexed(importedTextFiles) { index, (name, bytes) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ){
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ){

                    Text(
                        text = "$name (${bytes.size} bytes)",
                    )
                    IconButton(
                        onClick = {
                           importedTextFiles = importedTextFiles
                               .toMutableList()
                               .also { it.removeAt(index)}
                        }
                    ){
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete"
                        )
                    }
                }
            }
        }

        item{
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { imageFilePicker.launch() },
                ) {
                    ButtonText("Single Image File")
                }

                Button(
                    onClick = { imageFileMultiplePicker.launch() },
                ) {
                    ButtonText("Multiple Image File")
                }
            }
        }

        itemsIndexed(importedImageFiles) { index, (name, bytes) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ){
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ){

                    Text("$name (${bytes.size} bytes)")
                    IconButton(
                        onClick = {
                            importedImageFiles = importedImageFiles
                                .toMutableList()
                                .also { it.removeAt(index)}
                        }
                    ){
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete"
                        )
                    }
                }
            }
        }

        item{
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { imagePicker.launch() },
                ) {
                    ButtonText("Single Image")
                }

                Button(
                    onClick = { imageMultiplePicker.launch() },
                ) {
                    ButtonText("Multiple Image")
                }
            }
        }
        itemsIndexed(importedImages) { index, imageBytes ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ){
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ){
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(
                                    MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = decodeImage(imageBytes.toByteArray()),
                            contentDescription = null
                        )

                    }

                    IconButton(
                        onClick = {
                            importedImages = importedImages
                                .toMutableList()
                                .also { it.removeAt(index)}
                        }
                    ){
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ButtonText(text: String) {
    Icon(Icons.Default.Add, contentDescription = null)
    Text(text)
}