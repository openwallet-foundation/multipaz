package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.compose.trustmanagement.TrustEntryVicalEntryViewer
import org.multipaz.compose.trustmanagement.TrustManagerModel

@Composable
fun TrustEntryVicalEntryScreen(
    trustManagerModel: TrustManagerModel,
    vicalTrustEntryId: String,
    certNum: Int
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .verticalScroll(scrollState)
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TrustEntryVicalEntryViewer(
            trustManagerModel = trustManagerModel,
            vicalTrustEntryId = vicalTrustEntryId,
            certNum = certNum
        )
    }
}
