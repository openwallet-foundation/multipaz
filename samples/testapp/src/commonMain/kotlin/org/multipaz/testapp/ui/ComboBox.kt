package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun<T: Any> ComboBox(
    headline: String,
    availableRequests: List<T>,
    comboBoxSelected: MutableState<T>,
    comboBoxExpanded: MutableState<Boolean>,
    getDisplayName: (T) -> String,
    onSelected: (index: Int, value: T) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                modifier = Modifier.padding(end = 16.dp),
                text = headline
            )

            ExposedDropdownMenuBox(
                expanded = comboBoxExpanded.value,
                onExpandedChange = {
                    comboBoxExpanded.value = !comboBoxExpanded.value
                }
            ) {
                TextField(
                    value = getDisplayName(comboBoxSelected.value),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = comboBoxExpanded.value) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )

                ExposedDropdownMenu(
                    expanded = comboBoxExpanded.value,
                    onDismissRequest = { comboBoxExpanded.value = false }
                ) {
                    availableRequests.forEachIndexed { n, item ->
                        DropdownMenuItem(
                            text = { Text(text = getDisplayName(item)) },
                            onClick = {
                                comboBoxSelected.value = item
                                comboBoxExpanded.value = false
                                onSelected(n, comboBoxSelected.value)
                            }
                        )
                    }
                }
            }
        }
    }
}
