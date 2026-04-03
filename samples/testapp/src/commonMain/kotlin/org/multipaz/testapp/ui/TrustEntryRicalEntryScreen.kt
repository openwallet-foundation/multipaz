package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.compose.trustmanagement.TrustEntryRicalEntryViewer
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.trustmanagement.TrustEntryBasedTrustManager


@Composable
fun TrustEntryRicalEntryScreen(
    trustManagerModel: TrustManagerModel,
    ricalTrustEntryId: String,
    certNum: Int
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .verticalScroll(scrollState)
            .fillMaxSize()
            .padding(8.dp),
    ) {
        TrustEntryRicalEntryViewer(
            trustManagerModel = trustManagerModel,
            ricalTrustEntryId = ricalTrustEntryId,
            certNum = certNum
        )
    }
}
