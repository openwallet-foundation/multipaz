package org.multipaz.compose.trustmanagement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import org.jetbrains.compose.resources.stringResource
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.toDataItem
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.cards.InfoCard
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.items.Item
import org.multipaz.compose.items.ItemList
import org.multipaz.compose.items.ItemWithImageAndText
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.rical.RicalCertificateInfo
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.mdoc.vical.VicalCertificateInfo
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.trust_entry_certificates_title
import org.multipaz.multipaz_compose.generated.resources.trust_entry_click_to_view_chain
import org.multipaz.multipaz_compose.generated.resources.trust_entry_details_certificate
import org.multipaz.multipaz_compose.generated.resources.trust_entry_extensions
import org.multipaz.multipaz_compose.generated.resources.trust_entry_no
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_data_title
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_details_id
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_details_type
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_details_url
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_details_valid_until
import org.multipaz.multipaz_compose.generated.resources.trust_entry_signer
import org.multipaz.multipaz_compose.generated.resources.trust_entry_test_only_label
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_data_title
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_issue
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_issued_at
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_next_update
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_provider
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_update_url
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_valid_until
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_details_version
import org.multipaz.multipaz_compose.generated.resources.trust_entry_warning_rical_just_imported
import org.multipaz.multipaz_compose.generated.resources.trust_entry_warning_vical_just_imported
import org.multipaz.multipaz_compose.generated.resources.trust_entry_warning_x509_just_imported
import org.multipaz.multipaz_compose.generated.resources.trust_entry_yes
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * A Composable that displays the full details of a specific trust entry.
 *
 * It conditionally renders detailed UI components based on the type of the trust entry
 * (Single X.509 Certificate, VICAL list, or RICAL list). It also provides informational
 * banners for newly imported entries reminding the user to verify the provider.
 *
 * @param trustManagerModel The presentation model holding the trust entries.
 * @param trustEntryId The unique identifier of the trust entry to display.
 * @param justImported True if the entry was recently added, triggering an informational banner.
 * @param imageLoader a [ImageLoader].
 * @param onViewVicalEntry Callback invoked to navigate to a specific certificate within a VICAL.
 * @param onViewRicalEntry Callback invoked to navigate to a specific certificate within a RICAL.
 * @param onViewCertificate Callback invoked to view a standalone X.509 certificate.
 * @param onViewCertificateChain Callback invoked to view the signer's certificate chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustEntryViewer(
    trustManagerModel: TrustManagerModel,
    trustEntryId: String,
    justImported: Boolean,
    imageLoader: ImageLoader,
    onViewVicalEntry: (vicalCertNum: Int) -> Unit,
    onViewRicalEntry: (ricalCertNum: Int) -> Unit,
    onViewCertificate: (certificate: X509Cert) -> Unit,
    onViewCertificateChain: (certificateChain: X509CertChain) -> Unit,
) {
    val entryInfo = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == trustEntryId
    }!!

    Column() {
        if (justImported) {
            InfoCard(
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = when (entryInfo.entry) {
                        is TrustEntryX509Cert -> stringResource(Res.string.trust_entry_warning_x509_just_imported)
                        is TrustEntryVical -> stringResource(Res.string.trust_entry_warning_vical_just_imported)
                        is TrustEntryRical -> stringResource(Res.string.trust_entry_warning_rical_just_imported)
                    }
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            entryInfo.RenderImage(
                size = 160.dp,
                imageLoader = imageLoader
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = entryInfo.getDisplayName(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            val items = mutableListOf<@Composable () -> Unit>()
            items.add {
                Item(stringResource(Res.string.trust_entry_test_only_label),
                    if (entryInfo.entry.metadata.testOnly) {
                        stringResource(Res.string.trust_entry_yes)
                    } else {
                        stringResource(Res.string.trust_entry_no)
                    }
                )
            }
            ItemList(
                title = null,
                items = items
            )

            when (entryInfo.entry) {
                is TrustEntryX509Cert -> {
                    X509CertViewer(certificate = entryInfo.entry.certificate)
                }
                is TrustEntryVical -> {
                    VicalDetails(
                        trustEntry = entryInfo.entry,
                        signedVical = entryInfo.signedVical!!,
                        onViewVicalEntry = onViewVicalEntry,
                        onViewCertificate = onViewCertificate,
                        onViewCertificateChain = onViewCertificateChain
                    )
                }
                is TrustEntryRical -> {
                    RicalDetails(
                        trustEntry = entryInfo.entry,
                        signedRical = entryInfo.signedRical!!,
                        onViewRicalEntry = onViewRicalEntry,
                        onViewCertificate = onViewCertificate,
                        onViewCertificateChain = onViewCertificateChain
                    )
                }
            }
        }
    }
}

@Composable
private fun VicalDetails(
    trustEntry: TrustEntryVical,
    signedVical: SignedVical,
    onViewVicalEntry: (vicalCertNum: Int) -> Unit,
    onViewCertificate: (certificate: X509Cert) -> Unit,
    onViewCertificateChain: (certificateChain: X509CertChain) -> Unit,
) {
    val items = mutableListOf<@Composable () -> Unit>()
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_version),
            text = signedVical.vical.version
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_provider),
            text = signedVical.vical.vicalProvider
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_issue),
            text = signedVical.vical.vicalIssueID.toString()
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_issued_at),
            text = formattedDateTime(signedVical.vical.date)
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_next_update),
            text = signedVical.vical.nextUpdate?.let {
                formattedDateTime(it)
            } ?: AnnotatedString("-")
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_valid_until),
            text = signedVical.vical.notAfter?.let {
                formattedDateTime(it)
            } ?: AnnotatedString("-")
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_update_url),
            text = signedVical.vical.vicalUrl ?: "-"
        )
    }
    items.add {
        Item(
            stringResource(Res.string.trust_entry_signer),
            stringResource(Res.string.trust_entry_click_to_view_chain),
            modifier = Modifier.clickable {
                onViewCertificateChain(signedVical.vicalProviderCertificateChain)
            }
        )
    }
    if (signedVical.vical.extensions.isNotEmpty()) {
        items.add {
            ItemWithExtensions(
                heading = stringResource(Res.string.trust_entry_extensions),
                extensions = signedVical.vical.extensions
            )
        }
    }
    ItemList(
        title = stringResource(Res.string.trust_entry_vical_data_title),
        items = items
    )

    val certItems = mutableListOf<@Composable () -> Unit>()
    signedVical.vical.certificateInfos.forEachIndexed { n, certificateInfo ->
        certItems.add {
            ItemWithImageAndText(
                modifier = Modifier.clickable { onViewVicalEntry(n) },
                image = { certificateInfo.RenderIconWithFallback() },
                heading = certificateInfo.displayNameWithFallback,
                text = stringResource(Res.string.trust_entry_details_certificate),
            )
        }
    }
    ItemList(
        title = stringResource(Res.string.trust_entry_certificates_title),
        items = certItems
    )
}

@Composable
private fun RicalDetails(
    trustEntry: TrustEntryRical,
    signedRical: SignedRical,
    onViewRicalEntry: (ricalCertNum: Int) -> Unit,
    onViewCertificate: (certificate: X509Cert) -> Unit,
    onViewCertificateChain: (certificateChain: X509CertChain) -> Unit,
) {
    val items = mutableListOf<@Composable () -> Unit>()
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_rical_details_type),
            text = signedRical.rical.type
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_version),
            text = signedRical.rical.version
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_provider),
            text = signedRical.rical.provider
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_rical_details_id),
            text = signedRical.rical.id?.toString() ?: "-"
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_issued_at),
            text = formattedDateTime(signedRical.rical.date)
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_vical_details_next_update),
            text = signedRical.rical.nextUpdate?.let {
                formattedDateTime(it)
            } ?: AnnotatedString("-")
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_rical_details_valid_until),
            text = signedRical.rical.notAfter?.let {
                formattedDateTime(it)
            } ?: AnnotatedString("-")
        )
    }
    items.add {
        Item(
            heading = stringResource(Res.string.trust_entry_rical_details_url),
            text = signedRical.rical.latestRicalUrl ?: "-"
        )
    }
    items.add {
        Item(
            stringResource(Res.string.trust_entry_signer),
            stringResource(Res.string.trust_entry_click_to_view_chain),
            modifier = Modifier.clickable {
                onViewCertificateChain(signedRical.ricalProviderCertificateChain)
            }
        )
    }
    if (signedRical.rical.extensions.isNotEmpty()) {
        items.add {
            ItemWithExtensions(
                heading = stringResource(Res.string.trust_entry_extensions),
                extensions = signedRical.rical.extensions
            )
        }
    }
    ItemList(
        title = stringResource(Res.string.trust_entry_rical_data_title),
        items = items
    )

    val certItems = mutableListOf<@Composable () -> Unit>()
    signedRical.rical.certificateInfos.forEachIndexed { n, certificateInfo ->
        certItems.add {
            ItemWithImageAndText(
                modifier = Modifier.clickable { onViewRicalEntry(n) },
                image = { certificateInfo.RenderIconWithFallback() },
                heading = certificateInfo.displayNameWithFallback,
                text = stringResource(Res.string.trust_entry_details_certificate),
            )
        }
    }
    ItemList(
        title = stringResource(Res.string.trust_entry_certificates_title),
        items = certItems
    )
}

/**
 * Shows a list of VICAL/RICAL extensions.
 *
 * @param heading will be shown in bold at the top.
 * @param extensions the extensions to show.
 * @param modifier a [Modifier].
 */
@Composable
internal fun ItemWithExtensions(
    heading: String,
    extensions: Map<String, DataItem>,
    modifier: Modifier = Modifier
) {
    val sb = StringBuilder()
    extensions.forEach { (key, value) ->
        val valueStr = Cbor.toDiagnostics(value, setOf(
            DiagnosticOption.PRETTY_PRINT
        ))
        sb.append("$key: $valueStr\n")
    }
    return Item(
        heading = heading,
        text = sb.toString(),
        modifier = modifier
    )
}


/**
 * Generates an avatar icon for a [VicalCertificateInfo] based on its display name.
 * Uses a deterministic background color and the name's initials.
 */
@Composable
internal fun VicalCertificateInfo.RenderIconWithFallback(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Branding.Current.collectAsState().value.AvatarIcon(
        size = size,
        name = displayNameWithFallback,
        additionalData = certificate.encoded.toByteArray(),
        modifier = modifier
    )
}

/**
 * Resolves the common name of the certificate subject as a display string,
 * falling back to the full subject name if a common name is not present.
 */
internal val VicalCertificateInfo.displayNameWithFallback: String
    get() {
        val subject = certificate.subject
        val commonName = subject.components[OID.COMMON_NAME.oid]
        if (commonName != null) {
            return commonName.value
        }
        return subject.name
    }

/**
 * Generates an avatar icon for a [RicalCertificateInfo] based on its display name.
 * Uses a deterministic background color and the name's initials.
 */
@Composable
internal fun RicalCertificateInfo.RenderIconWithFallback(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Branding.Current.collectAsState().value.AvatarIcon(
        size = size,
        name = displayNameWithFallback,
        additionalData = certificate.encoded.toByteArray(),
        modifier = modifier
    )
}

/**
 * Resolves the common name of the certificate subject as a display string,
 * falling back to the full subject name if a common name is not present.
 */
internal val RicalCertificateInfo.displayNameWithFallback: String
    get() {
        val subject = certificate.subject
        val commonName = subject.components[OID.COMMON_NAME.oid]
        if (commonName != null) {
            return commonName.value
        }
        return subject.name
    }