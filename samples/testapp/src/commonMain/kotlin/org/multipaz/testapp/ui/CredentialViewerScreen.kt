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
import androidx.compose.runtime.collectAsState
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
import org.multipaz.compose.document.DocumentModel
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
    val documentInfos = documentModel.documentInfos.collectAsState().value
    val documentInfo = documentInfos.find { it.document.identifier == documentId }
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
            Text("No credential for documentId $documentId credentialId $credentialId")
        } else {
            KeyValuePairText("Class", credentialInfo.credential::class.simpleName.toString())
            KeyValuePairText("Identifier", credentialInfo.credential.identifier)
            KeyValuePairText("Domain", credentialInfo.credential.domain)
            KeyValuePairText("Certified", if (credentialInfo.credential.isCertified) "Yes" else "No")
            if (credentialInfo.credential.isCertified) {
                KeyValuePairText(
                    "Valid From",
                    formattedDateTime(credentialInfo.credential.validFrom)
                )
                KeyValuePairText(
                    "Valid Until",
                    formattedDateTime(credentialInfo.credential.validUntil)
                )
                KeyValuePairText(
                    "Issuer provided data",
                    "${credentialInfo.credential.issuerProvidedData.size} bytes"
                )
                KeyValuePairText("Usage Count", credentialInfo.credential.usageCount.toString())
                RevocationStatusSection(credentialInfo.credential)
                when (credentialInfo.credential) {
                    is MdocCredential -> {
                        val issuerSigned = Cbor.decode(credentialInfo.credential.issuerProvidedData.toByteArray())
                        val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
                        val msoBytes = issuerAuth.payload!!
                        KeyValuePairText("MSO size", "${msoBytes.size} bytes")
                        KeyValuePairText(
                            "ISO mdoc DocType",
                            (credentialInfo.credential as MdocCredential).docType
                        )
                        KeyValuePairText(
                            keyText = "ISO mdoc DS Key Certificate",
                            valueText = buildAnnotatedString {
                                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                                    append("Click for details")
                                }
                            },
                            modifier = Modifier.clickable {
                                coroutineScope.launch {
                                    val certChain =
                                        issuerAuth.unprotectedHeaders[
                                            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
                                        ]!!.asX509CertChain
                                    onViewCertificateChain(
                                        Cbor.encode(certChain.toDataItem()).toBase64Url()
                                    )
                                }
                            }
                        )
                    }

                    is SdJwtVcCredential -> {
                        KeyValuePairText(
                            "Verifiable Credential Type",
                            (credentialInfo.credential as SdJwtVcCredential).vct
                        )
                        // TODO: Show cert chain for key used to sign issuer-signed data. Involves
                        //  getting this over the network as specified in section 5 "JWT VC Issuer Metadata"
                        //  of https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/ ... how annoying
                    }
                }
            }

            if (credentialInfo.credential is SecureAreaBoundCredential) {
                KeyValuePairText(
                    "Secure Area",
                    (credentialInfo.credential as SecureAreaBoundCredential).secureArea.displayName
                )
                KeyValuePairText(
                    "Secure Area Identifier",
                    (credentialInfo.credential as SecureAreaBoundCredential).secureArea.identifier
                )
                KeyValuePairText(
                    "Device Key Algorithm",
                    credentialInfo.keyInfo!!.algorithm.description
                )
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
                            val attestation = (credentialInfo.credential as SecureAreaBoundCredential).getAttestation()
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

            if (credentialInfo.credential.isCertified) {
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
}
