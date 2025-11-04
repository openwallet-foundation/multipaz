package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.multipaz.cbor.Cbor
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.testapp.DocumentModel
import org.multipaz.util.toBase64Url
import kotlinx.coroutines.launch
import org.multipaz.compose.datetime.formattedDateTime

@Composable
fun CredentialViewerScreen(
    documentModel: DocumentModel,
    documentId: String,
    credentialId: String,
    showToast: (message: String) -> Unit,
    onViewCertificateChain: (encodedCertificateData: String) -> Unit,
    onViewCredentialClaims: (documentId: String, credentialId: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val documentInfo = documentModel.documentInfos[documentId]
    val credentialInfo = documentInfo?.credentialInfos?.find { it.credential.identifier == credentialId  }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (credentialInfo == null) {
            Text("No credential for documentId ${documentId} credentialId ${credentialId}")
        } else {
            KeyValuePairText("Class", credentialInfo.credential::class.simpleName.toString())
            KeyValuePairText("Identifier", credentialInfo.credential.identifier)
            KeyValuePairText("Domain", credentialInfo.credential.domain)
            KeyValuePairText("Valid From", formattedDateTime(credentialInfo.credential.validFrom))
            KeyValuePairText("Valid Until", formattedDateTime(credentialInfo.credential.validUntil))
            KeyValuePairText("Certified", if (credentialInfo.credential.isCertified) "Yes" else "No")
            KeyValuePairText("Issuer provided data", "${credentialInfo.credential.issuerProvidedData.size} bytes")
            KeyValuePairText("Usage Count", credentialInfo.credential.usageCount.toString())
            when (credentialInfo.credential) {
                is MdocCredential -> {
                    val issuerSigned = Cbor.decode(credentialInfo.credential.issuerProvidedData)
                    val issuerAuth = issuerSigned.get("issuerAuth").asCoseSign1
                    val msoBytes = issuerAuth.payload!!
                    KeyValuePairText("MSO size", "${msoBytes.size} bytes")
                    KeyValuePairText("ISO mdoc DocType", credentialInfo.credential.docType)
                    KeyValuePairText(
                        keyText = "ISO mdoc DS Key Certificate",
                        valueText = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                                append("Click for details")
                            }
                        },
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                val issuerSigned = Cbor.decode(credentialInfo.credential.issuerProvidedData)
                                val issuerAuthCoseSign1 = issuerSigned["issuerAuth"].asCoseSign1
                                val certChain =
                                    issuerAuthCoseSign1.unprotectedHeaders[
                                        CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
                                    ]!!.asX509CertChain
                                onViewCertificateChain(Cbor.encode(certChain.toDataItem()).toBase64Url())
                            }
                        }
                    )
                }
                is SdJwtVcCredential -> {
                    KeyValuePairText("Verifiable Credential Type", credentialInfo.credential.vct)
                    // TODO: Show cert chain for key used to sign issuer-signed data. Involves
                    //  getting this over the network as specified in section 5 "JWT VC Issuer Metadata"
                    //  of https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/ ... how annoying
                }
            }

            if (credentialInfo.credential is SecureAreaBoundCredential) {
                KeyValuePairText("Secure Area", credentialInfo.credential.secureArea.displayName)
                KeyValuePairText("Secure Area Identifier", credentialInfo.credential.secureArea.identifier)
                KeyValuePairText("Device Key Algorithm", credentialInfo.keyInfo!!.algorithm.description)
                KeyValuePairText("Device Key Invalidated",
                    buildAnnotatedString {
                        if (credentialInfo.keyInvalidated) {
                            withStyle(style = SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )) {
                                append("YES")
                            }
                        } else {
                            append("No")
                        }
                    })
                KeyValuePairText(
                    keyText = "Device Key Attestation",
                    valueText = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("Click for details")
                        }
                    },
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            val attestation = credentialInfo.credential.getAttestation()
                            if (attestation.certChain != null) {
                                onViewCertificateChain(Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url())
                            } else {
                                showToast("No attestation for Device Key")
                            }
                        }
                    }
                )
            } else {
                KeyValuePairText("Secure Area", "N/A")
            }

            KeyValuePairText(
                keyText = "Claims",
                valueText = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                        append("Click for details")
                    }
                },
                modifier = Modifier.clickable {
                    coroutineScope.launch {
                        onViewCredentialClaims(documentId, credentialId)
                    }
                }
            )

        }
    }
}
