package org.multipaz.testapp.ui.trustmanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.trustmanagement.TrustEntryInfo
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.trustmanagement.TrustEntryBasedTrustManager

/**
 * A Composable that displays the details of a specific individual certificate
 * embedded within a larger VICAL trust entry.
 *
 * @param trustManagerModel A [TrustManagerModel].
 * @param vicalTrustEntryId The identifier of the parent VICAL trust entry.
 * @param certNum The index position of the specific certificate within the VICAL's certificate list.
 */
@Composable
fun TrustEntryVicalEntryViewer(
    trustManagerModel: TrustManagerModel,
    vicalTrustEntryId: String,
    certNum: Int
) {
    val info = trustManagerModel.trustManagerInfos.collectAsState().value?.find {
        it.entry.identifier == vicalTrustEntryId
    } ?: return

    val vical = info.signedVical!!.vical
    val vicalCertInfo = vical.certificateInfos[certNum]

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        vicalCertInfo.RenderIconWithFallback(size = 160.dp)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = vicalCertInfo.displayNameWithFallback,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        FloatingItemList(
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            title = "VICAL entry"
        ) {
            FloatingItemHeadingAndText(
                heading = "Document Types",
                text = vicalCertInfo.docTypes.joinToString("\n")
            )
            if (vicalCertInfo.extensions.isNotEmpty()) {
                ItemWithExtensions(
                    heading = "Extensions",
                    extensions = vicalCertInfo.extensions
                )
            }
        }

        X509CertViewer(certificate = vicalCertInfo.certificate)
    }
}
