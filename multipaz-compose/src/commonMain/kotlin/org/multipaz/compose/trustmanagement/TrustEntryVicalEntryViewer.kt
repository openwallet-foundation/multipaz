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
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.trust_entry_extensions
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_entry_document_types
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_entry_title

/**
 * A Composable that displays the details of a specific individual certificate
 * embedded within a larger VICAL trust entry.
 *
 * @param trustManagerModel The presentation model holding the root VICAL trust entry.
 * @param vicalTrustEntryId The identifier of the parent VICAL trust entry.
 * @param certNum The index position of the specific certificate within the VICAL's certificate list.
 */
@Composable
fun TrustEntryVicalEntryViewer(
    trustManagerModel: TrustManagerModel,
    vicalTrustEntryId: String,
    certNum: Int
) {
    val trustEntryInfo = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == vicalTrustEntryId
    }!!
    val vical = trustEntryInfo.signedVical!!.vical
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

        FloatingItemList(title = stringResource(Res.string.trust_entry_vical_entry_title)) {
            FloatingItemHeadingAndText(
                heading = stringResource(Res.string.trust_entry_vical_entry_document_types),
                text = vicalCertInfo.docTypes.joinToString("\n")
            )
            if (vicalCertInfo.extensions.isNotEmpty()) {
                ItemWithExtensions(
                    heading = stringResource(Res.string.trust_entry_extensions),
                    extensions = vicalCertInfo.extensions
                )
            }
        }

        X509CertViewer(certificate = vicalCertInfo.certificate)
    }
}