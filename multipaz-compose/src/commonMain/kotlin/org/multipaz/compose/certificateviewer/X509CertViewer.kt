package org.multipaz.compose.certificateviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.asn1.OID
import org.multipaz.compose.datetime.durationFromNowText
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.crypto.X509Cert
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
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_oid
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_basic_info
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_extensions
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_issuer
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_public_key_info
import org.multipaz.multipaz_compose.generated.resources.certificate_viewer_sub_subject
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
    val data = remember { CertificateViewData.from(certificate) }
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        BasicInfo(data)
        Subject(data)
        Issuer(data)
        PublicKeyInfo(data)
        Extensions(data)
    }
}

@Composable
private fun BasicInfo(data: CertificateViewData) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    FloatingItemList(
        title = stringResource(Res.string.certificate_viewer_sub_basic_info),
    ) {
        FloatingItemHeadingAndText(
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
        FloatingItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_serial_number),
            data.serialNumber
        )
        FloatingItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_valid_from),
            formattedDateTime(
                instant = data.validFrom,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )
        FloatingItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_valid_until),
            formattedDateTime(
                instant = data.validUntil,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )

        val now = Clock.System.now()
        if (now > data.validUntil) {
            FloatingItemHeadingAndText(
                "Validity Info",
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
            FloatingItemHeadingAndText(
                "Validity Info",
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
            FloatingItemHeadingAndText(
                "Validity Info",
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

    FloatingItemList(
        title = stringResource(Res.string.certificate_viewer_sub_subject),
    ) {
        data.subject.forEach { (oid, value) ->
            val res = oidToResourceMap[oid]
            if (res != null) {
                FloatingItemHeadingAndText(stringResource(res), value)
            } else {
                FloatingItemHeadingAndText(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
}

@Composable
private fun Issuer(data: CertificateViewData) {
    if (data.issuer.isEmpty()) return

    FloatingItemList(
        title = stringResource(Res.string.certificate_viewer_sub_issuer),
    ) {
        data.issuer.forEach { (oid, value) ->
            val res = oidToResourceMap[oid]
            if (res != null) {
                FloatingItemHeadingAndText(stringResource(res), value)
            } else {
                FloatingItemHeadingAndText(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
}

@Composable
private fun PublicKeyInfo(data: CertificateViewData) {
    FloatingItemList(
        title = stringResource(Res.string.certificate_viewer_sub_public_key_info),
    ) {
        FloatingItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_pk_algorithm),
            data.pkAlgorithm
        )
        if (data.pkNamedCurve != null) {
            FloatingItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_k_pk_named_curve),
                data.pkNamedCurve
            )
        }
        FloatingItemHeadingAndText(
            stringResource(Res.string.certificate_viewer_k_pk_value),
            data.pkValue
        )
    }
}

@Composable
private fun Extensions(data: CertificateViewData) {
    if (data.extensions.isEmpty()) return

    FloatingItemList(
        title = stringResource(Res.string.certificate_viewer_sub_extensions),
    ) {
        data.extensions.forEach { (isCritical, oid, value) ->
            FloatingItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_critical),
                if (isCritical) {
                    stringResource(Res.string.certificate_viewer_critical_yes)
                } else {
                    stringResource(Res.string.certificate_viewer_critical_no)
                }
            )
            FloatingItemHeadingAndText(
                stringResource(Res.string.certificate_viewer_oid),
                oid
            )
            FloatingItemHeadingAndText(
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