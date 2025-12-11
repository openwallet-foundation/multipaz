package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.multipaz.compose.carousels.DocumentCarousel
import org.multipaz.compose.document.DocumentModel

@Composable
fun DocumentCarouselScreen(documentModel: DocumentModel) {
    Column {
        DocumentCarousel(
            Modifier
                .padding(vertical = 18.dp)
                .fillMaxWidth(),
            documentModel = documentModel,
            onDocumentClicked = {}
        ){
            FallBackEmptyMessage()
        }
    }
}

@Composable
private fun FallBackEmptyMessage() {
    // TODO: Add a FAB for adding documents
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "No Documents",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Press + to add one",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )

    }
}