package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.encodeImageToPng
import org.multipaz.compose.rememberUiBoundCoroutineScope
import org.multipaz.compose.sharemanager.ShareManager
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mpzpass.MpzPass
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Composable
fun GenerateMpzPassScreen(
    promptModel: PromptModel,
    documentTypeRepository: DocumentTypeRepository,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberUiBoundCoroutineScope { promptModel }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            Text(AnnotatedString.fromMarkdown(
                "To generate a `.mpzpass` file for a particular document type, " +
                        "click one of the buttons below. This file will then be shared using a sharesheet so you can " +
                        "import it on other devices"
            ))
        }

        for (documentType in documentTypeRepository.documentTypes) {
            item {
                if (documentType.mdocDocumentType != null) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val container = generateMpzPass(
                                        documentType = documentType,
                                        generateMdoc = true,
                                    )
                                    val shareManager = ShareManager()
                                    val epochSeconds = Clock.System.now().epochSeconds
                                    val typeStr = documentType.displayName.replace(" ", "-").lowercase()
                                    shareManager.shareDocument(
                                        content = Cbor.encode(container.toDataItem()),
                                        filename = "specimen-$typeStr-$epochSeconds.mpzpass",
                                        mimeType = "application/vnd.multipaz.mpzpass",
                                        title = "Share MpzPass for document type ${documentType.displayName} (ISO mdoc)"
                                    )
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    e.printStackTrace()
                                    showToast("Error generating specimen: ${e.message}")
                                }
                            }
                        },
                        content = { Text("MpzPass for ${documentType.displayName} (ISO mdoc)") }
                    )
                }

                if (documentType.jsonDocumentType != null) {
                    val format = if (documentType.jsonDocumentType!!.keyBound) {
                        "IETF SD-JWT VC"
                    } else {
                        "Keyless IETF SD-JWT VC"
                    }
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val container = generateMpzPass(
                                        documentType = documentType,
                                        generateMdoc = false,
                                    )
                                    val shareManager = ShareManager()
                                    val epochSeconds = Clock.System.now().epochSeconds
                                    val typeStr = documentType.displayName.replace(" ", "-").lowercase()
                                    shareManager.shareDocument(
                                        content = Cbor.encode(container.toDataItem()),
                                        filename = "specimen-$typeStr-$epochSeconds.mpzpass",
                                        mimeType = "application/vnd.multipaz.mpzpass",
                                        title = "Share MpzPass for document type ${documentType.displayName} ($format)"
                                    )
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    e.printStackTrace()
                                    showToast("Error generating specimen: ${e.message}")
                                }
                            }
                        },
                        content = { Text("MpzPass for ${documentType.displayName} ($format)") }
                    )
                }
            }
        }
    }
}

private suspend fun generateMpzPass(
    documentType: DocumentType,
    generateMdoc: Boolean
): MpzPass {
    val now = Clock.System.now().truncateToWholeSeconds()
    val signedAt = now - 1.days
    val expectedUpdate = now + 30.days
    val validFrom = now - 1.days
    val validUntil = now + 365.days
    val iacaValidFrom = validFrom
    val iacaValidUntil = validUntil
    val dsValidFrom = validFrom
    val dsValidUntil = validUntil

    val iacaKeyPub = EcPublicKey.fromPem(
        """
                    -----BEGIN PUBLIC KEY-----
                    MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE+QDye70m2O0llPXMjVjxVZz3m5k6agT+
                    wih+L79b7jyqUl99sbeUnpxaLD+cmB3HK3twkA7fmVJSobBc+9CDhkh3mx6n+YoH
                    5RulaSWThWBfMyRjsfVODkosHLCDnbPV
                    -----END PUBLIC KEY-----
                """.trimIndent().trim(),
    )
    val iacaKey = EcPrivateKey.fromPem(
        """
                    -----BEGIN PRIVATE KEY-----
                    MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCcRuzXW3pW2h9W8pu5
                    /CSR6JSnfnZVATq+408WPoNC3LzXqJEQSMzPsI9U1q+wZ2yhZANiAAT5APJ7vSbY
                    7SWU9cyNWPFVnPebmTpqBP7CKH4vv1vuPKpSX32xt5SenFosP5yYHccre3CQDt+Z
                    UlKhsFz70IOGSHebHqf5igflG6VpJZOFYF8zJGOx9U4OSiwcsIOds9U=
                    -----END PRIVATE KEY-----
                """.trimIndent().trim(),
        iacaKeyPub
    )

    val iacaCert = MdocUtil.generateIacaCertificate(
        iacaKey = AsymmetricKey.anonymous(iacaKey),
        subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
        serial = ASN1Integer.fromRandom(numBits = 128),
        validFrom = iacaValidFrom,
        validUntil = iacaValidUntil,
        issuerAltNameUrl = "https://github.com/openwallet-foundation/multipaz",
        crlUrl = "https://github.com/openwallet-foundation/multipaz/crl"
    )

    val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val dsCert = MdocUtil.generateDsCertificate(
        iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
        dsKey = dsPrivateKey.publicKey,
        subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
        serial = ASN1Integer.fromRandom(numBits = 128),
        validFrom = dsValidFrom,
        validUntil = dsValidUntil,
    )
    val dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsPrivateKey)


    val storage = EphemeralStorage()

    val softwareSecureArea = SoftwareSecureArea.create(storage)
    val secureAreaRepository = SecureAreaRepository.Builder()
        .add(softwareSecureArea)
        .build()

    val documentStore = buildDocumentStore(
        storage = storage,
        secureAreaRepository = secureAreaRepository
    ) {}

    val doc = documentStore.createDocument(
        displayName = "SPECIMEN",
        typeDisplayName = documentType.displayName
    )
    val specimenCardArt = Branding.Current.value.renderFallbackCardArt(
        doc
    )
    doc.edit {
        cardArt = encodeImageToPng(specimenCardArt)
    }

    if (generateMdoc) {
        val credential = documentType.createMdocCredentialWithSampleData(
            document = doc,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = expectedUpdate,
            domain = "mdoc",
        )
        return credential.exportToMpzPass()
    } else {
        if (documentType.jsonDocumentType!!.keyBound) {
            val credential = documentType.createKeyBoundSdJwtVcCredentialWithSampleData(
                document = doc,
                secureArea = softwareSecureArea,
                createKeySettings = CreateKeySettings(),
                dsKey = dsKey,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                domain = "sdjwtvc",
            )
            return credential.exportToMpzPass()
        } else {
            val credential = documentType.createKeylessSdJwtVcCredentialWithSampleData(
                document = doc,
                dsKey = dsKey,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                domain = "sdjwtvc",
            )
            return credential.exportToMpzPass()
        }
    }
}