package org.multipaz.compose.certificateviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.asn1.OID
import org.multipaz.compose.datetime.durationFromNowText
import org.multipaz.compose.datetime.formattedDateTime
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
    val sections = mutableListOf<@Composable () -> Unit>()
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_type),
            stringResource(Res.string.certificate_viewer_version_text, data.version)
        )
    }
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_serial_number),
            data.serialNumber
        )
    }
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_valid_from),
            formattedDateTime(
                instant = data.validFrom,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )
    }
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_valid_until),
            formattedDateTime(
                instant = data.validUntil,
                dateStyle = FormatStyle.FULL,
                timeStyle = FormatStyle.LONG,
            )
        )
    }

    val now = Clock.System.now()
    if (now > data.validUntil) {
        sections.add {
            KeyValuePairLine(
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
        }
    } else if (data.validFrom > now) {
        sections.add {
            KeyValuePairLine(
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
        }
    } else {
        sections.add {
            KeyValuePairLine(
                "Validity Info",
                stringResource(
                    Res.string.certificate_viewer_valid_now,
                    durationFromNowText(data.validUntil)
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    RenderSection(
        // Little bit of an easter-egg but very useful: Copy the PEM-encoded certificate
        // to the clipboard when user taps the "Basic Information" string.
        //
        modifier = Modifier.clickable {
            // TODO: Use LocalClipboard when ClipEntry is available to common,
            //  code (see https://youtrack.jetbrains.com/issue/CMP-7624 for status)
            clipboardManager.setText(AnnotatedString(data.pem))
        },
        title = stringResource(Res.string.certificate_viewer_sub_basic_info),
        sections = sections,
    )
}

@Composable
private fun Subject(data: CertificateViewData) {
    if (data.subject.isEmpty()) return

    val sections = mutableListOf<@Composable () -> Unit>()
    data.subject.forEach { (oid, value) ->
        val res = oidToResourceMap[oid]
        if (res != null) {
            sections.add {
                KeyValuePairLine(stringResource(res), value)
            }
        } else {
            sections.add {
                KeyValuePairLine(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
    RenderSection(
        title = stringResource(Res.string.certificate_viewer_sub_subject),
        sections = sections
    )
}

@Composable
private fun Issuer(data: CertificateViewData) {
    if (data.issuer.isEmpty()) return

    val sections = mutableListOf<@Composable () -> Unit>()
    data.issuer.forEach { (oid, value) ->
        val res = oidToResourceMap[oid]
        if (res != null) {
            sections.add {
                KeyValuePairLine(stringResource(res), value)
            }
        } else {
            sections.add {
                KeyValuePairLine(stringResource(Res.string.certificate_viewer_k_other_name, oid), value)
            }
        }
    }
    RenderSection(
        title = stringResource(Res.string.certificate_viewer_sub_issuer),
        sections = sections
    )
}

@Composable
private fun PublicKeyInfo(data: CertificateViewData) {
    val sections = mutableListOf<@Composable () -> Unit>()
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_pk_algorithm),
            data.pkAlgorithm
        )
    }
    if (data.pkNamedCurve != null) {
        sections.add {
            KeyValuePairLine(
                stringResource(Res.string.certificate_viewer_k_pk_named_curve),
                data.pkNamedCurve
            )
        }
    }
    sections.add {
        KeyValuePairLine(
            stringResource(Res.string.certificate_viewer_k_pk_value),
            data.pkValue
        )
    }
    RenderSection(
        title = stringResource(Res.string.certificate_viewer_sub_public_key_info),
        sections = sections
    )
}

@Composable
private fun Extensions(data: CertificateViewData) {
    if (data.extensions.isEmpty()) return

    val sections = mutableListOf<@Composable () -> Unit>()
    data.extensions.forEach { (isCritical, oid, value) ->
        sections.add {
            KeyValuePairLine(
                stringResource(Res.string.certificate_viewer_critical),
                if (isCritical) {
                    stringResource(Res.string.certificate_viewer_critical_yes)
                } else {
                    stringResource(Res.string.certificate_viewer_critical_no)
                }
            )
            KeyValuePairLine(
                stringResource(Res.string.certificate_viewer_oid),
                oid
            )
            KeyValuePairLine(
                stringResource(Res.string.certificate_viewer_value),
                value
            )
        }
    }
    RenderSection(
        title = stringResource(Res.string.certificate_viewer_sub_extensions),
        sections = sections
    )
}

@Composable
private fun RenderSection(
    modifier: Modifier = Modifier,
    sections: List<@Composable () -> Unit>,
    title: String,
) {
    Text(
        modifier = modifier.padding(start = 15.dp, top = 15.dp, end = 15.dp, bottom = 0.dp),
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary,
    )

    Column(
        modifier = Modifier
            .padding(15.dp)
            .dropShadow(
                shape = RoundedCornerShape(16.dp),
                shadow = Shadow(
                    radius = 10.dp,
                    spread = 5.dp,
                    color = Color.Black.copy(alpha = 0.05f),
                    offset = DpOffset(x = 0.dp, 2.dp)
                )
            ),
    ) {
        for (n in sections.indices) {
            val section = sections[n]
            val isFirst = (n == 0)
            val isLast = (n == sections.size - 1)
            val rounded = 16.dp
            val firstRounded = if (isFirst) rounded else 0.dp
            val endRound = if (isLast) rounded else 0.dp
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape = RoundedCornerShape(firstRounded, firstRounded, endRound, endRound))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    section()
                }
            }
            if (!isLast) {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun KeyValuePairLine(
    key: String,
    valueText: AnnotatedString,
) {
    if (valueText.isEmpty()) {
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(8.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        SelectionContainer {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


@Composable
private fun KeyValuePairLine(
    key: String,
    valueText: String,
) {
    if (valueText.isEmpty()) {
        return
    }
    return KeyValuePairLine(key, AnnotatedString(valueText))
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
