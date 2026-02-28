package org.multipaz.compose.trustmanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.compose.certificateviewer.X509CertViewer

/**
 * A Composable that displays the details of a specific individual certificate
 * embedded within a larger RICAL trust entry.
 *
 * @param trustManagerModel The presentation model holding the root RICAL trust entry.
 * @param ricalTrustEntryId The identifier of the parent RICAL trust entry.
 * @param certNum The index position of the specific certificate within the RICAL's certificate list.
 */
@Composable
fun TrustEntryRicalEntryViewer(
    trustManagerModel: TrustManagerModel,
    ricalTrustEntryId: String,
    certNum: Int
) {
    val trustEntryInfo = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == ricalTrustEntryId
    }!!
    val rical = trustEntryInfo.signedRical!!.rical
    val ricalCertInfo = rical.certificateInfos[certNum]

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ricalCertInfo.RenderIconWithFallback(size = 160.dp)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = ricalCertInfo.displayNameWithFallback,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        X509CertViewer(certificate = ricalCertInfo.certificate)
    }
}