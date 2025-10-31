package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.DataItem
import org.multipaz.claim.organizeByNamespace
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.decodeImage
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.verification.JsonVerifiedPresentation
import org.multipaz.verification.MdocVerifiedPresentation
import org.multipaz.verification.VerificationUtil.verifyMdocDeviceResponse
import org.multipaz.verification.VerificationUtil.verifyOpenID4VPResponse
import org.multipaz.testapp.ShowResponseMetadata
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

private const val TAG = "ShowResponse"

private sealed class Value

private data class ValueText(
    val text: String
): Value()

private data class ValueSize(
    val size: Long
): Value()

private data class ValueImage(
    val text: String?,
    val image: ImageBitmap
): Value()

private data class ValueDateTime(
    val dateTime: Instant?
): Value()

private data class ValueDuration(
    val duration: Duration?
): Value()

private data class ValueCertChain(
    val certChain: X509CertChain
): Value()

private data class Line(
    val header: String,
    val value: Value,
    val onClick: (() -> Unit)? = null
)

private data class Section(
    val header: String,
    val lines: List<Line>
)

private data class VerificationResult(
    val sections: List<Section>
)

@Composable
fun ShowResponse(
    vpToken: JsonObject?,
    deviceResponse: DataItem?,
    sessionTranscript: DataItem,
    nonce: ByteString?,
    eReaderKey: EcPrivateKey?,
    metadata: ShowResponseMetadata?,
    issuerTrustManager: TrustManager,
    documentTypeRepository: DocumentTypeRepository?,
    zkSystemRepository: ZkSystemRepository?,
    onViewCertChain: ((certChain: X509CertChain) -> Unit)?
) {
    val coroutineScope = rememberCoroutineScope()
    val verificationError = remember { mutableStateOf<Throwable?>(null) }
    val verficationResult = remember { mutableStateOf<VerificationResult?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val now = Clock.System.now()
            try {
                verficationResult.value = parseResponse(
                    now = now,
                    vpToken = vpToken,
                    deviceResponse = deviceResponse,
                    sessionTranscript = sessionTranscript,
                    nonce = nonce,
                    eReaderKey = eReaderKey,
                    metadata = metadata,
                    documentTypeRepository = documentTypeRepository,
                    zkSystemRepository = zkSystemRepository,
                    issuerTrustManager = issuerTrustManager,
                    onViewCertChain = onViewCertChain
                )
            } catch (e: Throwable) {
                Logger.e(TAG, "Error parsing response", e)
                verificationError.value = e
            }
        }
    }

    if (verificationError.value != null) {
        Text(text = verificationError.value!!.message ?: "Failed")
    } else if (verficationResult.value != null) {
        for (section in verficationResult.value!!.sections) {
            val entries = mutableListOf<@Composable () -> Unit>()
            for (line in section.lines) {
                entries.add {
                    Column(
                        Modifier.fillMaxWidth().padding(8.dp)
                            .clickable(
                                enabled = line.onClick != null
                            ) {
                                line.onClick!!.invoke()
                            }
                    ) {
                        Text(
                            text = line.header,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        when (line.value) {
                            is ValueDateTime -> {
                                line.value.dateTime?.let {
                                    Text(text =  formattedDateTime(instant = it))
                                } ?: Text(text = "-")
                            }
                            is ValueDuration -> {
                                line.value.duration?.let {
                                    Text(
                                        text = "${it.inWholeMilliseconds} msec"
                                    )
                                } ?: Text(text = "-")
                            }
                            is ValueImage -> {
                                line.value.text?.let {
                                    Text(text = line.value.text)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Image(
                                    bitmap = line.value.image,
                                    modifier = Modifier.size(200.dp),
                                    contentDescription = null
                                )
                            }
                            is ValueCertChain -> {
                                Text(text = "Click to view")
                            }

                            is ValueText -> {
                                Text(text = line.value.text)
                            }

                            is ValueSize -> {
                                Text(text = "${line.value.size} bytes")
                            }
                        }
                    }
                }
            }
            EntryList(
                title = section.header,
                entries = entries
            )
        }
    }
}

private suspend fun parseResponse(
    now: Instant,
    vpToken: JsonObject?,
    deviceResponse: DataItem?,
    sessionTranscript: DataItem,
    nonce: ByteString?,
    eReaderKey: EcPrivateKey?,
    metadata: ShowResponseMetadata?,
    documentTypeRepository: DocumentTypeRepository?,
    zkSystemRepository: ZkSystemRepository?,
    issuerTrustManager: TrustManager,
    onViewCertChain: ((certChain: X509CertChain) -> Unit)?
): VerificationResult {
    val sections = mutableListOf<Section>()
    var lines: MutableList<Line>

    val verifiedPresentations = if (deviceResponse != null) {
        verifyMdocDeviceResponse(
            now = now,
            deviceResponse = deviceResponse,
            sessionTranscript = sessionTranscript,
            eReaderKey = eReaderKey?.let {
                AsymmetricKey.anonymous(it, it.curve.defaultKeyAgreementAlgorithm)
            },
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository
        )
    } else if (vpToken != null) {
        verifyOpenID4VPResponse(
            now = now,
            vpToken = vpToken,
            sessionTranscript = sessionTranscript,
            nonce = nonce!!,
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository
        )
    } else {
        throw IllegalStateException("Either deviceResponse or vpToken must be non-null")
    }

    if (metadata != null) {
        lines = mutableListOf()
        lines.add(Line("Engagement Type", ValueText(metadata.engagementType)))
        lines.add(Line("Transfer Protocol", ValueText(metadata.transferProtocol)))
        lines.add(Line("Request size", ValueSize(metadata.requestSize)))
        lines.add(Line("Response size", ValueSize(metadata.responseSize)))
        lines.add(Line("Tap to engagement received", ValueDuration(
            metadata.durationMsecNfcTapToEngagement?.toDuration(DurationUnit.MILLISECONDS)
        )))
        lines.add(Line("Engagement received to request sent", ValueDuration(
            metadata.durationMsecEngagementReceivedToRequestSent?.toDuration(DurationUnit.MILLISECONDS)
        )))
        lines.add(Line("Request sent to response received", ValueDuration(
            metadata.durationMsecRequestSentToResponseReceived.toDuration(DurationUnit.MILLISECONDS)
        )))
        var totalMsec = 0L
        metadata.durationMsecNfcTapToEngagement?.let { totalMsec += it }
        metadata.durationMsecEngagementReceivedToRequestSent?.let { totalMsec += it }
        totalMsec += metadata.durationMsecRequestSentToResponseReceived
        lines.add(Line("Total duration", ValueDuration(totalMsec.toDuration(DurationUnit.MILLISECONDS))))
        sections.add(
            Section(
                header = "Transfer info",
                lines = lines
            )
        )
    }

    verifiedPresentations.forEachIndexed { vpNum, vp ->
        lines = mutableListOf()
        when (vp) {
            is MdocVerifiedPresentation -> {
                lines.add(Line("Credential format", ValueText("ISO mdoc")))
                lines.add(Line("DocType", ValueText(vp.docType)))
                lines.add(Line("Issuer DS curve", ValueText(vp.documentSignerCertChain.certificates.first().ecPublicKey.curve.name)))
                val trustResult =
                    issuerTrustManager.verify(vp.documentSignerCertChain.certificates, now)
                if (trustResult.isTrusted) {
                    val tpName =
                        trustResult.trustPoints.first().metadata?.displayName?.let { " ($it)" } ?: ""
                    lines.add(Line("Issuer Trusted", ValueText("Yes$tpName")))
                } else {
                    lines.add(Line("Issuer Trusted", ValueText("No")))
                }
                lines.add(
                    Line(
                        "Issuer certificate chain",
                        ValueCertChain(vp.documentSignerCertChain),
                        { onViewCertChain?.let { it(vp.documentSignerCertChain) } }
                    )
                )
                if (vp.zkpUsed) {
                    lines.add(Line("ZK proof", ValueText("Successfully verified \uD83E\uDE84")))
                }
                lines.add(Line("Valid from", ValueDateTime(vp.validFrom)))
                lines.add(Line("Valid until", ValueDateTime(vp.validUntil)))
                lines.add(Line("Signed at", ValueDateTime(vp.signedAt)))
                lines.add(Line("Expected update", ValueDateTime(vp.expectedUpdate)))

                for (n in listOf(0, 1)) {
                    val claims = if (n == 0) { vp.issuerSignedClaims } else { vp.deviceSignedClaims }
                    for ((namespace, claims) in claims.organizeByNamespace()) {
                        val namespaceHeader = if (n == 0) { "Namespace" } else { "Namespace (device-signed)" }
                        lines.add(Line(namespaceHeader, ValueText(namespace)))
                        for (claim in claims) {
                            val line = if (claim.attribute != null && claim.attribute!!.type == DocumentAttributeType.Picture) {
                                val image = decodeImage(claim.value.asBstr)
                                Line(claim.displayName, ValueImage(claim.render(), image))
                            } else {
                                Line(claim.displayName, ValueText(claim.render()))
                            }
                            lines.add(line)
                        }
                    }
                }
            }

            is JsonVerifiedPresentation -> {
                lines.add(Line("Credential format", ValueText("IETF SD-JWT VC")))
                lines.add(Line("Verifiable Credential Type", ValueText(vp.vct)))
                lines.add(Line("Issuer DS curve", ValueText(vp.documentSignerCertChain.certificates.first().ecPublicKey.curve.name)))
                val trustResult =
                    issuerTrustManager.verify(vp.documentSignerCertChain.certificates, now)
                if (trustResult.isTrusted) {
                    val tpName =
                        trustResult.trustPoints.first().metadata?.displayName?.let { " ($it)" } ?: ""
                    lines.add(Line("Issuer Trusted", ValueText("Yes$tpName")))
                } else {
                    lines.add(Line("Issuer Trusted", ValueText("No")))
                }
                lines.add(
                    Line(
                        "Issuer certificate chain",
                        ValueCertChain(vp.documentSignerCertChain),
                        { onViewCertChain?.let { it(vp.documentSignerCertChain) } }
                    )
                )
                if (vp.zkpUsed) {
                    lines.add(Line("ZK proof", ValueText("Successfully verified \uD83E\uDE84")))
                }
                lines.add(Line("Valid from", ValueDateTime(vp.validFrom)))
                lines.add(Line("Valid until", ValueDateTime(vp.validUntil)))
                lines.add(Line("Signed at", ValueDateTime(vp.signedAt)))
                lines.add(Line("Expected update", ValueDateTime(vp.expectedUpdate)))
                for (n in listOf(0, 1)) {
                    val (claims, trailer) = if (n == 0) {
                        Pair(vp.issuerSignedClaims, "")
                    } else {
                        Pair(vp.deviceSignedClaims, " (Device Signed)")
                    }
                    for (claim in claims) {
                        val line = if (claim.attribute != null && claim.attribute!!.type == DocumentAttributeType.Picture) {
                            val image = decodeImage(claim.value.jsonPrimitive.content.fromBase64Url())
                            Line(claim.displayName + trailer, ValueImage(claim.render(), image))
                        } else {
                            Line(claim.displayName + trailer, ValueText(claim.render()))
                        }
                        lines.add(line)
                    }
                }
            }
        }
        sections.add(
            Section(
                header = "Document ${vpNum + 1} of ${verifiedPresentations.size}",
                lines = lines
            )
        )
    }
    return VerificationResult(sections)
}

@Composable
private fun EntryList(
    modifier: Modifier = Modifier,
    title: String?,
    entries: List<@Composable () -> Unit>,
) {
    if (title != null) {
        Text(
            modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    for (n in entries.indices) {
        val section = entries[n]
        val isFirst = (n == 0)
        val isLast = (n == entries.size - 1)
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
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
