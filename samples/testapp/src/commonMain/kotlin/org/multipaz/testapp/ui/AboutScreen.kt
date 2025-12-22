package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.util.Platform

@Composable
fun AboutScreen() {
    LazyColumn(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        item {
            Text("This is an interactive test application for elements of " +
                    "the Multipaz project which are not easy to write unit " +
                    "tests for.")
        }
        item {
            Text("Version ${Platform.version}")
        }
    }
}