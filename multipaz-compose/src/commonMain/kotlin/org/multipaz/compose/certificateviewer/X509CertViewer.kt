package org.multipaz.compose.certificateviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.asn1.OID
import org.multipaz.compose.datetime.clockForTesting
import org.multipaz.compose.datetime.durationFromNowText
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.items.SegmentedItemHeadingAndText
import org.multipaz.compose.items.SegmentedItemList
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.datetime.FormatStyle
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_critical
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_critical_no
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_critical_yes
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_common_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_country_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_locality_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_org_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_org_unit_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_other_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_pk_algorithm
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_pk_named_curve
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_pk_value
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_serial_number
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_state_name
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_type
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_valid_from
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_valid_until
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_k_validity_info
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_oid
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_basic_info
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_extensions
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_issuer
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_public_key_info
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_subject
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_tab_intermediate
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_tab_intermediate_n
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_tab_leaf
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_tab_root
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_valid_now
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_validity_in_the_future
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_validity_in_the_past
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_value
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_version_text
import kotlin.time.Clock

/**
 * Shows a X.509 certificate.
 *
 * @param modifier a [Modifier].
 * @param certificate the [X509Cert] to show.
 */
@Composable
fun X509CertViewer(
    modifier: Modifier = Modifier,
    certificate: X509Cert,
) {
    val data = remember(certificate) { CertificateViewData.from(certificate) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicInfo(data)
        Subject(data)
        Issuer(data)
        PublicKeyInfo(data)
        Extensions(data)
    }
}

/**
 * Shows a X.509 certificate chain.
 *
 * When the chain contains more than one certificate, a segmented button row is displayed at the
 * top allowing the user to switch between the leaf, intermediate, and root certificates while
 * the certificate details scroll vertically below it.
 *
 * @param modifier a [Modifier].
 * @param certChain the [X509CertChain] to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X509CertViewer(
    modifier: Modifier = Modifier,
    certChain: X509CertChain,
) {
    val certificates = certChain.certificates
    check(certificates.isNotEmpty())
    var selectedCertIndex by remember(certChain) { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedCertIndex) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if (certificates.size > 1) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                certificates.forEachIndexed { index, _ ->
                    val label = when {
                        index == 0 -> stringResource(Res.string.certificate_viewer_tab_leaf)
                        index == certificates.size - 1 -> stringResource(Res.string.certificate_viewer_tab_root)
                        certificates.size == 3 -> stringResource(Res.string.certificate_viewer_tab_intermediate)
                        else -> stringResource(Res.string.certificate_viewer_tab_intermediate_n, index)
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = certificates.size
                        ),
                        selected = selectedCertIndex == index,
                        onClick = { selectedCertIndex = index },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        val currentCert = certificates.getOrNull(selectedCertIndex) ?: certificates[0]
        key(selectedCertIndex, currentCert) {
            X509CertViewer(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(bottom = 16.dp),
                certificate = currentCert
            )
        }
    }
}

@Composable
private fun BasicInfo(data: CertificateViewData) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    SegmentedItemList(
        title = stringResource(Res.string.certificate_viewer_sub_basic_info),
    ) {
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_type),
            stringResource(Res.string.certificate_viewer_version_text, data.version),
            // Little bit of an easter-egg but very useful: Copy the PEM-encoded certificate
            // to the clipboard when user taps the "Basic Information" string.
            //
            modifier = Modifier.clickable {
                // TODO: Use LocalClipboard when ClipEntry is available to common,
                //  code (see https://youtrack.jetbrains.com/issue/CMP-7624 for status)
                clipboardManager.setText(AnnotatedString(data.pem))
            },
        )
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_serial_number),
            data.serialNumber
        )
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_valid_from),
            formattedDateTime(
                instant = data.validFrom,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_valid_until),
            formattedDateTime(
                instant = data.validUntil,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )

        val now = (clockForTesting ?: Clock.System).now()
        if (now > data.validUntil) {
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_k_validity_info),
                buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.error)) {
                        append(
                            stringResource(
                                Res.string.certificate_viewer_validity_in_the_past,
                                durationFromNowText(data.validUntil)
                            )
                        )
                    }
                }
            )
        } else if (data.validFrom > now) {
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_k_validity_info),
                buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.error)) {
                        append(
                            stringResource(
                                Res.string.certificate_viewer_validity_in_the_future,
                                durationFromNowText(data.validFrom)
                            )
                        )
                    }
                }
            )
        } else {
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_k_validity_info),
                stringResource(
                    Res.string.certificate_viewer_valid_now,
                    durationFromNowText(data.validUntil)
                )
            )
        }
    }
}

@Composable
private fun Subject(data: CertificateViewData) {
    if (data.subject.isEmpty()) return

    SegmentedItemList(
        title = stringResource(Res.string.certificate_viewer_sub_subject),
    ) {
        data.subject.forEach { (oid, value) ->
            val res = oidToResourceMap[oid]
            if (res != null) {
                SegmentedItemHeadingAndText(stringResource(res), value)
            } else {
                SegmentedItemHeadingAndText(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
}

@Composable
private fun Issuer(data: CertificateViewData) {
    if (data.issuer.isEmpty()) return

    SegmentedItemList(
        title = stringResource(Res.string.certificate_viewer_sub_issuer),
    ) {
        data.issuer.forEach { (oid, value) ->
            val res = oidToResourceMap[oid]
            if (res != null) {
                SegmentedItemHeadingAndText(stringResource(res), value)
            } else {
                SegmentedItemHeadingAndText(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
}

@Composable
private fun PublicKeyInfo(data: CertificateViewData) {
    SegmentedItemList(
        title = stringResource(Res.string.certificate_viewer_sub_public_key_info),
    ) {
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_pk_algorithm),
            data.pkAlgorithm
        )
        if (data.pkNamedCurve != null) {
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_k_pk_named_curve),
                data.pkNamedCurve
            )
        }
        SegmentedItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_pk_value),
            data.pkValue
        )
    }
}

@Composable
private fun Extensions(data: CertificateViewData) {
    if (data.extensions.isEmpty()) return

    data.extensions.forEachIndexed { n, (isCritical, oid, value) ->
        SegmentedItemList(
            title = if (n == 0) {
                stringResource(Res.string.certificate_viewer_sub_extensions)
            } else {
                null
            },
        ) {
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_critical),
                if (isCritical) {
                    stringResource(Res.string.certificate_viewer_critical_yes)
                } else {
                    stringResource(Res.string.certificate_viewer_critical_no)
                }
            )
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_oid),
                oid
            )
            SegmentedItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_value),
                value
            )
        }
    }
}

private val oidToResourceMap: Map<String, StringResource> by lazy {
    mapOf(
        OID.COMMON_NAME.oid to Res.string.certificate_viewer_k_common_name,
        OID.SERIAL_NUMBER.oid to Res.string.certificate_viewer_k_serial_number,
        OID.COUNTRY_NAME.oid to Res.string.certificate_viewer_k_country_name,
        OID.LOCALITY_NAME.oid to Res.string.certificate_viewer_k_locality_name,
        OID.STATE_OR_PROVINCE_NAME.oid to Res.string.certificate_viewer_k_state_name,
        OID.ORGANIZATION_NAME.oid to Res.string.certificate_viewer_k_org_name,
        OID.ORGANIZATIONAL_UNIT_NAME.oid to Res.string.certificate_viewer_k_org_unit_name,
        // TODO: Add support for other OIDs from RFC 5280 Annex A, as needed.
    )
}