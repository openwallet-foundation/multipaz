@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MajorType
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mpzpass.MpzPass
import org.multipaz.mpzpass.MpzPassIsoMdoc
import org.multipaz.util.UUID
import org.multipaz.util.fromBase64
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64
import org.multipaz.util.toHex
import org.multipaz.util.truncateToWholeSeconds
import react.FC
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.img
import react.useEffectOnce
import react.useState
import web.blob.Blob
import web.blob.BlobPropertyBag
import web.canvas.CanvasRenderingContext2D
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.HTMLAnchorElement
import web.html.HTMLCanvasElement
import web.html.InputType
import web.url.URL
import kotlin.time.Duration.Companion.days

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class EditableDataElement(
    val id: String = UUID.randomUUID().toString(),
    var identifier: String,
    var type: String, // "string", "int", "bool", "full_date", "cbor_hex", "raw_file"
    var value: String,
    var fileBytes: ByteArray? = null,
    var fileName: String = ""
)

data class EditableNamespace(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var elements: MutableList<EditableDataElement> = mutableListOf()
)

val MpzPassCreatorComponent = FC {
    // Pass Metadata
    var passName by useState("Erika's Driving License")
    var passTypeName by useState("Utopia Driving License")
    var uniqueId by useState(UUID.randomUUID().toString())
    var versionStr by useState("0")
    var updateUrl by useState("")
    var userAuthenticationRequired by useState(false)
    var shareable by useState(true)
    var readerIdentifiers by useState<List<String>>(emptyList())
    var customAkiInput by useState("")
    var customAkiError by useState("")
    var credentialCountStr by useState("1")

    // Card Art options: "auto" or "custom"
    var cardArtMode by useState("auto")
    var customCardArtBytes by useState<ByteArray?>(null)
    var customCardArtFileName by useState("")

    // Key & Certificate options: "auto" or "custom"
    var certMode by useState("auto")
    var customIacaCertPem by useState("")
    var customIacaPrivateKeyPem by useState("")
    var customDsCertPem by useState("")
    var customDsPrivateKeyPem by useState("")

    // Pass Signature options: "auto", "custom", or "none"
    var passSignatureMode by useState("auto")
    var customPassCertPem by useState("")
    var customPassPrivateKeyPem by useState("")

    // ISO mDoc options
    var docType by useState("org.iso.18013.5.1.mDL")
    var namespaces by useState<List<EditableNamespace>>(emptyList())

    // Generation State
    var isGenerating by useState(false)
    var statusMessage by useState("")
    var createdPassBytes by useState<ByteArray?>(null)

    fun parseSampleDataItem(elemId: String, dataItem: DataItem): EditableDataElement {
        val (type, valStr, bytes, fName) = when {
            dataItem is Bstr -> {
                val b = dataItem.value
                val ext = if (elemId == "portrait") "jpg" else "bin"
                Tuple4("raw_file", "", b, "$elemId.$ext")
            }
            dataItem is Tagged && dataItem.tagNumber == Tagged.FULL_DATE_STRING -> {
                Tuple4("full_date", dataItem.taggedItem.asTstr, null, "")
            }
            dataItem is Tstr -> {
                Tuple4("string", dataItem.asTstr, null, "")
            }
            dataItem is Uint || dataItem is Nint -> {
                Tuple4("int", dataItem.asNumber.toString(), null, "")
            }
            dataItem is Simple && (dataItem.value == Simple.TRUE.value || dataItem.value == Simple.FALSE.value) -> {
                Tuple4("bool", (dataItem.value == Simple.TRUE.value).toString(), null, "")
            }
            else -> {
                Tuple4("cbor_hex", Cbor.encode(dataItem).toHex(), null, "")
            }
        }
        return EditableDataElement(
            identifier = elemId,
            type = type,
            value = valStr,
            fileBytes = bytes,
            fileName = fName
        )
    }

    fun convertDocumentTypeToEditableNamespaces(docTypeObj: DocumentType): List<EditableNamespace> {
        val mdocDocType = docTypeObj.mdocDocumentType ?: return emptyList()
        val result = mutableListOf<EditableNamespace>()

        for ((nsName, nsObj) in mdocDocType.namespaces) {
            val editableElements = mutableListOf<EditableDataElement>()
            for ((elemId, elemObj) in nsObj.dataElements) {
                val sampleVal = elemObj.attribute.sampleValueMdoc ?: continue
                editableElements.add(parseSampleDataItem(elemId, sampleVal))
            }
            if (editableElements.isNotEmpty()) {
                result.add(EditableNamespace(name = nsName, elements = editableElements))
            }
        }
        return result
    }

    fun populateSampleMdl() {
        val docTypeObj = DrivingLicense.getDocumentType()
        docType = docTypeObj.mdocDocumentType!!.docType
        passName = "Erika's Driving License"
        passTypeName = docTypeObj.displayName
        namespaces = convertDocumentTypeToEditableNamespaces(docTypeObj)
    }

    fun populateSampleEuPid() {
        val docTypeObj = EUPersonalID.getDocumentType()
        docType = docTypeObj.mdocDocumentType!!.docType
        passName = "Erika's EU Personal ID"
        passTypeName = docTypeObj.displayName
        namespaces = convertDocumentTypeToEditableNamespaces(docTypeObj)
    }

    useEffectOnce {
        populateSampleMdl()
    }

    fun generateCanvasCardArt(title: String, subtitle: String): ByteArray {
        val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
        canvas.width = 600
        canvas.height = 380
        val ctx = canvas.asDynamic().getContext("2d").unsafeCast<CanvasRenderingContext2D>()

        // Draw slate gradient background
        val grad = ctx.createLinearGradient(0.0, 0.0, 600.0, 380.0)
        grad.addColorStop(0.0, "#1e293b")
        grad.addColorStop(1.0, "#0f172a")
        ctx.fillStyle = grad
        ctx.fillRect(0.0, 0.0, 600.0, 380.0)

        // Accent border line
        ctx.lineWidth = 4.0
        ctx.strokeStyle = "#38bdf8"
        ctx.strokeRect(16.0, 16.0, 568.0, 348.0)

        // Top emblem box
        ctx.fillStyle = "#2563eb"
        ctx.fillRect(36.0, 36.0, 60.0, 60.0)

        ctx.fillStyle = "#ffffff"
        ctx.font = "bold 26px sans-serif"
        ctx.fillText("🪪", 50.0, 76.0)

        // Pass Title
        ctx.fillStyle = "#ffffff"
        ctx.font = "bold 26px sans-serif"
        ctx.fillText(title.take(32), 112.0, 66.0)

        // Subtitle
        ctx.fillStyle = "#94a3b8"
        ctx.font = "16px sans-serif"
        ctx.fillText(subtitle.take(38), 112.0, 92.0)

        // Decorative lower panel
        ctx.fillStyle = "#334155"
        ctx.fillRect(36.0, 290.0, 160.0, 40.0)

        ctx.fillStyle = "#38bdf8"
        ctx.font = "bold 14px monospace"
        ctx.fillText("ISO 18013-5 mDoc", 50.0, 315.0)

        val dataUrl = canvas.toDataURL("image/png")
        val base64Str = dataUrl.substringAfter("base64,")
        return base64Str.fromBase64()
    }

    fun buildCborDataItemForValue(elem: EditableDataElement): DataItem {
        val cleanVal = elem.value.trim()
        return when (elem.type) {
            "int" -> (cleanVal.toLongOrNull() ?: 0L).toDataItem()
            "bool" -> (cleanVal.lowercase() == "true" || cleanVal == "1").toDataItem()
            "full_date" -> {
                try {
                    LocalDate.parse(cleanVal).toDataItemFullDate()
                } catch (e: Throwable) {
                    Tagged(Tagged.FULL_DATE_STRING, Tstr(cleanVal))
                }
            }
            "cbor_hex" -> {
                try {
                    Cbor.decode(cleanVal.fromHex())
                } catch (e: Throwable) {
                    Tstr(cleanVal)
                }
            }
            "raw_file" -> {
                val bytes = elem.fileBytes ?: try { cleanVal.fromHex() } catch (e: Throwable) { cleanVal.encodeToByteArray() }
                Bstr(bytes)
            }
            else -> Tstr(cleanVal)
        }
    }

    fun createAndDownloadMpzPass() {
        isGenerating = true
        statusMessage = "Generating pass keys, MSO, and certificates..."
        mainScope.launch {
            try {
                // 1. Device Key
                val deviceKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)

                // 2. Issuer Signing Keys & Certificates (IACA + DS)
                val dsCertifiedKey: AsymmetricKey.X509Certified = if (certMode == "auto") {
                    val now = Clock.System.now().truncateToWholeSeconds()
                    val iacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
                    val iacaCert = MdocUtil.generateIacaCertificate(
                        iacaKey = AsymmetricKey.anonymous(iacaKey),
                        subject = X500Name.fromName("CN=Multipaz Test IACA,C=ZZ"),
                        serial = ASN1Integer(1L),
                        validFrom = now,
                        validUntil = now + 3650.days,
                        issuerAltNameUrl = "http://iaca.example.com",
                        crlUrl = "http://iaca.example.com/crl"
                    )
                    val iacaCertifiedKey = AsymmetricKey.X509CertifiedExplicit(
                        certChain = X509CertChain(listOf(iacaCert)),
                        privateKey = iacaKey
                    )

                    val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
                    val dsCert = MdocUtil.generateDsCertificate(
                        iacaKey = iacaCertifiedKey,
                        dsKey = dsPrivateKey.publicKey,
                        subject = X500Name.fromName("CN=Multipaz Test DS,C=ZZ"),
                        serial = ASN1Integer(2L),
                        validFrom = now,
                        validUntil = now + 365.days
                    )
                    AsymmetricKey.X509CertifiedExplicit(
                        certChain = X509CertChain(listOf(dsCert, iacaCert)),
                        privateKey = dsPrivateKey
                    )
                } else {
                    val iacaCert = X509Cert.fromPem(customIacaCertPem.trim())
                    val dsCert = X509Cert.fromPem(customDsCertPem.trim())
                    val dsPrivateKey = EcPrivateKey.fromPem(customDsPrivateKeyPem.trim(), dsCert.ecPublicKey)
                    AsymmetricKey.X509CertifiedExplicit(
                        certChain = X509CertChain(listOf(dsCert, iacaCert)),
                        privateKey = dsPrivateKey
                    )
                }

                // 3. Build IssuerNamespaces
                val issuerNamespaces = buildIssuerNamespaces {
                    for (ns in namespaces) {
                        if (ns.name.trim().isNotEmpty()) {
                            addNamespace(ns.name.trim()) {
                                for (elem in ns.elements) {
                                    if (elem.identifier.trim().isNotEmpty()) {
                                        val dataItemVal = buildCborDataItemForValue(elem)
                                        addDataElement(elem.identifier.trim(), dataItemVal)
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Generate ISO mDoc Credentials (based on credentialCount)
                val count = (credentialCountStr.toIntOrNull() ?: 1).coerceAtLeast(1)
                val isoMdocList = mutableListOf<MpzPassIsoMdoc>()

                val protectedHeaders = mapOf<CoseLabel, DataItem>(
                    Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                )
                val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
                    Cose.COSE_LABEL_X5CHAIN.toCoseLabel to dsCertifiedKey.certChain.toCoseX5Chain()
                )
                val now = Clock.System.now().truncateToWholeSeconds()

                for (i in 0 until count) {
                    val deviceKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
                    val mso = MobileSecurityObject(
                        version = "1.0",
                        docType = docType.trim(),
                        signedAt = now,
                        validFrom = now,
                        validUntil = now + 365.days,
                        expectedUpdate = null,
                        digestAlgorithm = Algorithm.SHA256,
                        valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
                        deviceKey = deviceKeyPrivate.publicKey
                    )
                    val msoBytes = Cbor.encode(mso.toDataItem())

                    val taggedEncodedMso = Cbor.encode(
                        Tagged(Tagged.ENCODED_CBOR, Bstr(msoBytes))
                    )

                    val issuerAuth = Cose.coseSign1Sign(
                        signingKey = dsCertifiedKey,
                        message = taggedEncodedMso,
                        includeMessageInPayload = true,
                        protectedHeaders = protectedHeaders,
                        unprotectedHeaders = unprotectedHeaders
                    )

                    isoMdocList.add(
                        MpzPassIsoMdoc(
                            docType = docType.trim(),
                            deviceKeyPrivate = deviceKeyPrivate,
                            issuerNamespaces = issuerNamespaces,
                            issuerAuth = issuerAuth
                        )
                    )
                }

                // 5. Card Art
                val cardArtBytes = if (cardArtMode == "custom" && customCardArtBytes != null) {
                    customCardArtBytes
                } else {
                    generateCanvasCardArt(passName, passTypeName)
                }

                // 6. Pass Envelope Signature
                var passSigningKey: AsymmetricKey? = null
                var passIssuerCertChain: X509CertChain? = null
                if (passSignatureMode != "none") {
                    if (passSignatureMode == "auto") {
                        val passIssuerKey = Crypto.createEcPrivateKey(EcCurve.P256)
                        val now = Clock.System.now().truncateToWholeSeconds()
                        val cert = X509Cert.Builder(
                            publicKey = passIssuerKey.publicKey,
                            signingKey = AsymmetricKey.anonymous(passIssuerKey),
                            serialNumber = ASN1Integer.fromRandom(128),
                            subject = X500Name.fromName("CN=$passName Issuer,O=Multipaz,C=US"),
                            issuer = X500Name.fromName("CN=$passName Issuer,O=Multipaz,C=US"),
                            validFrom = now,
                            validUntil = now + 365.days
                        ).build()
                        passSigningKey = AsymmetricKey.anonymous(passIssuerKey)
                        passIssuerCertChain = X509CertChain(listOf(cert))
                    } else {
                        val passCert = X509Cert.fromPem(customPassCertPem.trim())
                        val passPrivKey = EcPrivateKey.fromPem(customPassPrivateKeyPem.trim(), passCert.ecPublicKey)
                        passSigningKey = AsymmetricKey.anonymous(passPrivKey)
                        passIssuerCertChain = X509CertChain(listOf(passCert))
                    }
                }

                // 7. Assemble MpzPass
                val readerIdByteStrings = readerIdentifiers.mapNotNull { hex ->
                    try {
                        ByteString(hex.fromHex())
                    } catch (e: Throwable) {
                        null
                    }
                }

                val mpzPass = MpzPass(
                    uniqueId = uniqueId.ifEmpty { UUID.randomUUID().toString() },
                    version = versionStr.toLongOrNull() ?: 0L,
                    updateUrl = updateUrl.trim().ifEmpty { null },
                    userAuthenticationRequired = userAuthenticationRequired,
                    readerIdentifiers = readerIdByteStrings,
                    shareable = shareable,
                    name = passName.ifEmpty { "Untitled Pass" },
                    typeName = passTypeName.ifEmpty { "ISO mDoc Pass" },
                    cardArt = cardArtBytes?.let { ByteString(*it) },
                    isoMdoc = isoMdocList,
                    sdJwtVc = emptyList()
                )

                val passDataItem = mpzPass.toDataItem(
                    signingKey = passSigningKey,
                    issuerCertificateChain = passIssuerCertChain
                )
                val passBytes = Cbor.encode(passDataItem)
                createdPassBytes = passBytes

                // 9. Download with MIME type application/vnd.multipaz.mpzpass
                val blob = Blob(
                    arrayOf(passBytes.unsafeCast<js.buffer.ArrayBuffer>()),
                    BlobPropertyBag(type = "application/vnd.multipaz.mpzpass")
                )
                val blobUrl = URL.createObjectURL(blob)
                val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
                anchor.href = blobUrl
                val currentDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val monthStr = (currentDateTime.month.ordinal + 1).toString().padStart(2, '0')
                val dayStr = currentDateTime.dayOfMonth.toString().padStart(2, '0')
                val hourStr = currentDateTime.hour.toString().padStart(2, '0')
                val minuteStr = currentDateTime.minute.toString().padStart(2, '0')
                val dateStr = "${currentDateTime.year}$monthStr$dayStr-$hourStr$minuteStr"
                val safeFileName = passName.lowercase().replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "pass" }
                val fullFileName = "$safeFileName-$dateStr.mpzpass"
                anchor.download = fullFileName
                anchor.click()
                URL.revokeObjectURL(blobUrl)

                statusMessage = "Successfully created and downloaded $fullFileName (${passBytes.size} bytes)!"
            } catch (e: Throwable) {
                statusMessage = "Error generating pass: ${e.message ?: e.toString()}"
            } finally {
                isGenerating = false
            }
        }
    }

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 16.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 32.px
        }

        h2 {
            css {
                fontSize = 1.8.rem
                fontWeight = FontWeight.bold
                margin = Margin(0.px, 0.px, 8.px, 0.px)
                color = Color("#f8fafc")
            }
            +"MpzPass Creator"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 28.px
            }
            +"Generate, configure, and issue lightweight `.mpzpass` credential files containing ISO mDocs."
        }

        // Section 1: Pass Display Metadata
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 16.px }
                +"🪪 Pass Display & Metadata"
            }

            div {
                css { display = Display.grid; gridTemplateColumns = "1fr 1fr".unsafeCast<GridTemplateColumns>(); gap = 16.px }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Pass Title (name):" }
                    input {
                        css {
                            width = 100.pct
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                        }
                        value = passName
                        onChange = { passName = it.target.value }
                    }
                }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Credential Type Name (typeName):" }
                    input {
                        css {
                            width = 100.pct
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                        }
                        value = passTypeName
                        onChange = { passTypeName = it.target.value }
                    }
                }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Unique Identifier (uniqueId):" }
                    div {
                        css { display = Display.flex; gap = 8.px }
                        input {
                            css {
                                flexGrow = number(1.0)
                                padding = 10.px
                                borderRadius = 6.px
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                background = Color("#1e293b")
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 13.px
                            }
                            value = uniqueId
                            onChange = { uniqueId = it.target.value }
                        }
                        button {
                            css {
                                background = Color("#334155")
                                border = None.none
                                color = Color("#f1f5f9")
                                padding = Padding(6.px, 12.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontSize = 13.px
                                hover { background = Color("#475569") }
                            }
                            onClick = { uniqueId = UUID.randomUUID().toString() }
                            +"🎲 New UUID"
                        }
                    }
                }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Pass Version:" }
                    input {
                        css {
                            width = 100.pct
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                        }
                        type = "number".unsafeCast<InputType>()
                        value = versionStr
                        onChange = { versionStr = it.target.value }
                    }
                }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Update URL (updateUrl, optional):" }
                    input {
                        css {
                            width = 100.pct
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                            fontFamily = "monospace".unsafeCast<FontFamily>()
                        }
                        value = updateUrl
                        placeholder = "https://example.com/pass-update"
                        onChange = { updateUrl = it.target.value }
                    }
                }

                div {
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                        input {
                            css { marginRight = 8.px }
                            type = "checkbox".unsafeCast<InputType>()
                            checked = userAuthenticationRequired
                            onChange = { userAuthenticationRequired = it.target.checked }
                        }
                        +"Require Platform User Authentication (userAuthenticationRequired)"
                    }
                }

                div {
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                        input {
                            css { marginRight = 8.px }
                            type = "checkbox".unsafeCast<InputType>()
                            checked = shareable
                            onChange = { shareable = it.target.checked }
                        }
                        +"Allow Pass Sharing (shareable)"
                    }
                    p {
                        css { color = Color("#94a3b8"); fontSize = 12.px; margin = Margin(4.px, 0.px, 0.px, 24.px) }
                        +"Permits holders to share or forward the raw .mpzpass file with others (e.g. via messaging apps, Quick Share, or email)."
                    }
                }

                div {
                    label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Number of Credentials to Create:" }
                    input {
                        css {
                            width = 100.pct
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                        }
                        type = "number".unsafeCast<InputType>()
                        min = "1"
                        max = "20"
                        value = credentialCountStr
                        onChange = { credentialCountStr = it.target.value }
                    }
                }
            }
        }

        // Section 2: Reader Authentication & Identifiers (readerIdentifiers)
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 16.px }
                +"🔍 Reader Authentication & Identifiers (readerIdentifiers, optional)"
            }

            p {
                css { color = Color("#94a3b8"); fontSize = 13.px; marginTop = 0.px; marginBottom = 16.px }
                +"If configured, the pass is only accessible to readers using reader authentication where a certificate in the x5chain contains an Authority Key Identifier (AKI) in this list."
            }

            // Configured AKIs list
            if (readerIdentifiers.isNotEmpty()) {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 8.px; marginBottom = 16.px }
                    readerIdentifiers.forEach { akiHex ->
                        val known = KNOWN_READERS.find { it.akiHex.equals(akiHex, ignoreCase = true) }
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                padding = Padding(8.px, 12.px)
                            }
                            div {
                                if (known != null) {
                                    div {
                                        css { fontWeight = FontWeight.bold; color = Color("#f1f5f9"); fontSize = 13.px }
                                        +known.name
                                    }
                                }
                                div {
                                    css { color = Color("#38bdf8"); fontFamily = "monospace".unsafeCast<FontFamily>(); fontSize = 12.px }
                                    +akiHex
                                }
                            }
                            button {
                                css {
                                    background = Color("#7f1d1d")
                                    color = Color("#fca5a5")
                                    border = None.none
                                    padding = Padding(4.px, 8.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                    hover { background = Color("#991b1b") }
                                }
                                onClick = {
                                    readerIdentifiers = readerIdentifiers.filter { it != akiHex }
                                }
                                +"✕ Remove"
                            }
                        }
                    }
                    button {
                        css {
                            alignSelf = AlignSelf.flexStart
                            background = Color("transparent")
                            color = Color("#f87171")
                            border = None.none
                            padding = Padding(4.px, 8.px)
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            textDecoration = TextDecoration.underline
                        }
                        onClick = { readerIdentifiers = emptyList() }
                        +"Clear All Reader Identifiers"
                    }
                }
            } else {
                p {
                    css { color = Color("#64748b"); fontStyle = FontStyle.italic; fontSize = 13.px; marginBottom = 16.px }
                    +"No reader identifiers configured. Pass will be accessible to all readers without restriction."
                }
            }

            // Quick add known readers
            div {
                css { marginBottom = 16.px }
                div {
                    css { color = Color("#cbd5e1"); fontSize = 13.px; fontWeight = FontWeight.bold; marginBottom = 8.px }
                    +"Quick Add Known Readers:"
                }
                div {
                    css { display = Display.flex; flexWrap = FlexWrap.wrap; gap = 8.px }
                    KNOWN_READERS.forEach { known ->
                        val isAdded = readerIdentifiers.any { it.equals(known.akiHex, ignoreCase = true) }
                        button {
                            css {
                                background = if (isAdded) Color("#334155") else Color("#1e293b")
                                color = if (isAdded) Color("#64748b") else Color("#38bdf8")
                                border = Border(1.px, LineStyle.solid, if (isAdded) Color("#475569") else Color("#2563eb"))
                                borderRadius = 6.px
                                padding = Padding(6.px, 12.px)
                                fontSize = 12.px
                                cursor = if (isAdded) Cursor.default else Cursor.pointer
                                if (!isAdded) {
                                    hover { background = Color("#2563eb"); color = Color("#ffffff") }
                                }
                            }
                            disabled = isAdded
                            onClick = {
                                if (!isAdded) {
                                    readerIdentifiers = readerIdentifiers + known.akiHex
                                }
                            }
                            +("${if (isAdded) "✓ " else "+ "}${known.name}")
                        }
                    }
                }
            }

            // Add custom AKI hex
            div {
                div {
                    css { color = Color("#cbd5e1"); fontSize = 13.px; fontWeight = FontWeight.bold; marginBottom = 8.px }
                    +"Add Custom Reader AKI (Hex):"
                }
                div {
                    css { display = Display.flex; gap = 8.px }
                    input {
                        css {
                            flexGrow = number(1.0)
                            padding = 10.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, if (customAkiError.isNotEmpty()) Color("#ef4444") else Color("#475569"))
                            background = Color("#1e293b")
                            color = Color("#f8fafc")
                            fontFamily = "monospace".unsafeCast<FontFamily>()
                            fontSize = 13.px
                        }
                        value = customAkiInput
                        placeholder = "e.g. b18439852f4a6eeabfea62adbc51d081f7488729"
                        onChange = {
                            customAkiInput = it.target.value
                            customAkiError = ""
                        }
                    }
                    button {
                        css {
                            background = Color("#2563eb")
                            color = Color("#ffffff")
                            border = None.none
                            padding = Padding(10.px, 18.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontWeight = FontWeight.bold
                            fontSize = 13.px
                            hover { background = Color("#1d4ed8") }
                        }
                        onClick = {
                            val trimmed = customAkiInput.trim().lowercase()
                            if (trimmed.isEmpty()) {
                                customAkiError = "AKI hex cannot be empty"
                            } else if (readerIdentifiers.any { it.equals(trimmed, ignoreCase = true) }) {
                                customAkiError = "AKI is already added"
                            } else {
                                try {
                                    trimmed.fromHex()
                                    readerIdentifiers = readerIdentifiers + trimmed
                                    customAkiInput = ""
                                    customAkiError = ""
                                } catch (e: Throwable) {
                                    customAkiError = "Invalid hex string"
                                }
                            }
                        }
                        +"+ Add AKI"
                    }
                }
                if (customAkiError.isNotEmpty()) {
                    div {
                        css { color = Color("#f87171"); fontSize = 12.px; marginTop = 4.px }
                        +customAkiError
                    }
                }
            }
        }

        // Section 3: Card Art Settings
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 16.px }
                +"🖼️ Card Art (Pass Image)"
            }

            div {
                css { display = Display.flex; gap = 16.px; marginBottom = 16.px }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "cardArtMode"
                        checked = cardArtMode == "auto"
                        onChange = { cardArtMode = "auto" }
                        css { marginRight = 8.px }
                    }
                    +"Auto-generate Card Art (Canvas with Pass Title)"
                }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "cardArtMode"
                        checked = cardArtMode == "custom"
                        onChange = { cardArtMode = "custom" }
                        css { marginRight = 8.px }
                    }
                    +"Upload Custom Image (PNG / JPEG)"
                }
            }

            if (cardArtMode == "custom") {
                div {
                    css { marginTop = 12.px }
                    label {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#f1f5f9")
                            padding = Padding(8.px, 16.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 13.px
                            fontWeight = FontWeight.bold
                            hover { background = Color("#475569") }
                        }
                        +"📁 Choose PNG / JPEG File"
                        input {
                            type = "file".unsafeCast<InputType>()
                            accept = "image/png,image/jpeg"
                            css { display = None.none }
                            onChange = { event ->
                                val fileList = event.target.asDynamic().files
                                if (fileList != null && fileList.length > 0) {
                                    val file = fileList[0].unsafeCast<File>()
                                    customCardArtFileName = file.name
                                    val reader = FileReader()
                                    reader.asDynamic().onload = {
                                        val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                        customCardArtBytes = Int8Array(arrayBuffer).toByteArray()
                                    }
                                    reader.readAsArrayBuffer(file)
                                }
                            }
                        }
                    }

                    if (customCardArtFileName.isNotEmpty()) {
                        span { css { color = Color("#38bdf8"); marginLeft = 12.px; fontSize = 13.px }; +customCardArtFileName }
                    }

                    customCardArtBytes?.let { bytes ->
                        div {
                            css { marginTop = 12.px }
                            img {
                                src = "data:image/png;base64,${bytes.toBase64()}"
                                alt = "Card Art Preview"
                                css {
                                    maxWidth = 200.px
                                    maxHeight = 120.px
                                    borderRadius = 8.px
                                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Issuer Keys & Certificates (IACA & DS)
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 16.px }
                +"🔐 Issuer Signing Keys & Certificates (IACA & DS)"
            }

            div {
                css { display = Display.flex; gap = 16.px; marginBottom = 16.px }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "certMode"
                        checked = certMode == "auto"
                        onChange = { certMode = "auto" }
                        css { marginRight = 8.px }
                    }
                    +"Auto-generate Testing IACA & DS Keys"
                }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "certMode"
                        checked = certMode == "custom"
                        onChange = { certMode = "custom" }
                        css { marginRight = 8.px }
                    }
                    +"Provide Custom Certificate / Private Key"
                }
            }

            if (certMode == "custom") {
                div {
                    css { display = Display.grid; gridTemplateColumns = "1fr 1fr".unsafeCast<GridTemplateColumns>(); gap = 16.px; marginTop = 16.px }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"IACA Certificate (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customIacaCertPem
                            placeholder = "-----BEGIN CERTIFICATE-----\n..."
                            onChange = { customIacaCertPem = it.target.value }
                        }
                    }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"IACA Private Key (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customIacaPrivateKeyPem
                            placeholder = "-----BEGIN PRIVATE KEY-----\n..."
                            onChange = { customIacaPrivateKeyPem = it.target.value }
                        }
                    }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"DS Certificate (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customDsCertPem
                            placeholder = "-----BEGIN CERTIFICATE-----\n..."
                            onChange = { customDsCertPem = it.target.value }
                        }
                    }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"DS Private Key (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customDsPrivateKeyPem
                            placeholder = "-----BEGIN PRIVATE KEY-----\n..."
                            onChange = { customDsPrivateKeyPem = it.target.value }
                        }
                    }
                }
            }
        }

        // Section: Pass Container Signature
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 16.px }
                +"🔏 Pass Container Signature (#6.18 COSE_Sign1)"
            }

            p {
                css { color = Color("#94a3b8"); fontSize = 13.px; marginTop = 0.px; marginBottom = 16.px }
                +"Digitally signs the pass container using COSE_Sign1 to ensure envelope integrity (display metadata, update URL, reader identifiers) and authenticate the issuer via X.509 certificate chain."
            }

            div {
                css { display = Display.flex; gap = 16.px; marginBottom = 16.px }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "passSignatureMode"
                        checked = passSignatureMode == "auto"
                        onChange = { passSignatureMode = "auto" }
                        css { marginRight = 8.px }
                    }
                    +"Auto-generate Pass Issuer Key & Certificate"
                }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "passSignatureMode"
                        checked = passSignatureMode == "custom"
                        onChange = { passSignatureMode = "custom" }
                        css { marginRight = 8.px }
                    }
                    +"Custom Signing Key & Certificate (PEM)"
                }

                label {
                    css { cursor = Cursor.pointer; color = Color("#f1f5f9"); fontSize = 14.px }
                    input {
                        type = "radio".unsafeCast<InputType>()
                        name = "passSignatureMode"
                        checked = passSignatureMode == "none"
                        onChange = { passSignatureMode = "none" }
                        css { marginRight = 8.px }
                    }
                    +"Unsigned Pass"
                }
            }

            if (passSignatureMode == "custom") {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px; marginTop = 12.px }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Pass Issuer Certificate (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customPassCertPem
                            placeholder = "-----BEGIN CERTIFICATE-----\n..."
                            onChange = { customPassCertPem = it.target.value }
                        }
                    }

                    div {
                        label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Pass Issuer Private Key (PEM):" }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 6.px
                                color = Color("#f8fafc")
                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                fontSize = 11.px
                            }
                            value = customPassPrivateKeyPem
                            placeholder = "-----BEGIN PRIVATE KEY-----\n..."
                            onChange = { customPassPrivateKeyPem = it.target.value }
                        }
                    }
                }
            }
        }

        // Section 5: ISO mDoc Credential Configuration
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 24.px
            }

            div {
                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 16.px }

                h3 {
                    css { fontSize = 1.2.rem; color = Color("#38bdf8"); margin = Margin(0.px, 0.px, 0.px, 0.px) }
                    +"📄 ISO mDoc Credential Configuration"
                }

                div {
                    css { display = Display.flex; gap = 8.px }

                    button {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#f1f5f9")
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            fontWeight = FontWeight.bold
                            hover { background = Color("#475569") }
                        }
                        onClick = { populateSampleMdl() }
                        +"⚡ Sample mDL"
                    }

                    button {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#f1f5f9")
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            fontWeight = FontWeight.bold
                            hover { background = Color("#475569") }
                        }
                        onClick = { populateSampleEuPid() }
                        +"⚡ Sample EU PID"
                    }
                }
            }

            div {
                css { marginBottom = 20.px }
                label { css { display = Display.block; color = Color("#cbd5e1"); marginBottom = 6.px; fontSize = 13.px }; +"Document Type (docType):" }
                input {
                    css {
                        width = 100.pct
                        padding = 10.px
                        borderRadius = 6.px
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        background = Color("#1e293b")
                        color = Color("#f8fafc")
                        fontFamily = "monospace".unsafeCast<FontFamily>()
                    }
                    value = docType
                    onChange = { docType = it.target.value }
                }
            }

            // Namespaces Loop
            for ((nsIndex, ns) in namespaces.withIndex()) {
                div {
                    css {
                        background = Color("#1e293b")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 10.px
                        padding = 16.px
                        marginBottom = 16.px
                    }

                    div {
                        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }

                        div {
                            css { display = Display.flex; gap = 8.px; alignItems = AlignItems.center; flexGrow = number(1.0) }
                            span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold; fontSize = 13.px }; +"Namespace:" }
                            input {
                                css {
                                    padding = Padding(6.px, 10.px)
                                    borderRadius = 6.px
                                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                                    background = Color("#0f172a")
                                    color = Color("#38bdf8")
                                    fontWeight = FontWeight.bold
                                    fontFamily = "monospace".unsafeCast<FontFamily>()
                                    width = 300.px
                                }
                                value = ns.name
                                onChange = { event ->
                                    val newName = event.target.asDynamic().value.unsafeCast<String>()
                                    namespaces = namespaces.mapIndexed { idx, item ->
                                        if (idx == nsIndex) item.copy(name = newName) else item
                                    }
                                }
                            }
                        }

                        button {
                            css {
                                background = Color("#7f1d1d")
                                color = Color("#fca5a5")
                                border = None.none
                                padding = Padding(4.px, 10.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontSize = 12.px
                            }
                            onClick = {
                                namespaces = namespaces.filterIndexed { idx, _ -> idx != nsIndex }
                            }
                            +"❌ Remove Namespace"
                        }
                    }

                    // Data Elements Table
                    table {
                        css { width = 100.pct; borderCollapse = BorderCollapse.collapse; fontSize = 13.px; marginBottom = 12.px }
                        thead {
                            tr {
                                th { css { padding = 6.px; textAlign = TextAlign.left; background = Color("#0f172a"); color = Color("#94a3b8") }; +"Element ID" }
                                th { css { padding = 6.px; textAlign = TextAlign.left; background = Color("#0f172a"); color = Color("#94a3b8"); width = 140.px }; +"Data Type" }
                                th { css { padding = 6.px; textAlign = TextAlign.left; background = Color("#0f172a"); color = Color("#94a3b8") }; +"Value" }
                                th { css { padding = 6.px; width = 40.px; background = Color("#0f172a") } }
                            }
                        }
                        tbody {
                            for ((elemIndex, elem) in ns.elements.withIndex()) {
                                tr {
                                    css { borderBottom = Border(1.px, LineStyle.solid, Color("#0f172a")) }
                                    td {
                                        css { padding = 6.px }
                                        input {
                                            css {
                                                width = 100.pct
                                                padding = 4.px
                                                borderRadius = 4.px
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                background = Color("#0f172a")
                                                color = Color("#f8fafc")
                                                fontFamily = "monospace".unsafeCast<FontFamily>()
                                            }
                                            value = elem.identifier
                                            onChange = { event ->
                                                val newId = event.target.asDynamic().value.unsafeCast<String>()
                                                namespaces = namespaces.mapIndexed { idx, item ->
                                                    if (idx == nsIndex) {
                                                        val updated = item.elements.toMutableList()
                                                        updated[elemIndex] = elem.copy(identifier = newId)
                                                        item.copy(elements = updated)
                                                    } else item
                                                }
                                            }
                                        }
                                    }
                                    td {
                                        css { padding = 6.px }
                                        select {
                                            css {
                                                width = 100.pct
                                                padding = 4.px
                                                borderRadius = 4.px
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                background = Color("#0f172a")
                                                color = Color("#cbd5e1")
                                                fontSize = 12.px
                                            }
                                            value = elem.type
                                            onChange = { event ->
                                                val newType = event.target.asDynamic().value.unsafeCast<String>()
                                                namespaces = namespaces.mapIndexed { idx, item ->
                                                    if (idx == nsIndex) {
                                                        val updated = item.elements.toMutableList()
                                                        updated[elemIndex] = elem.copy(type = newType)
                                                        item.copy(elements = updated)
                                                    } else item
                                                }
                                            }
                                            option { value = "string"; +"String" }
                                            option { value = "int"; +"Integer" }
                                            option { value = "bool"; +"Boolean" }
                                            option { value = "full_date"; +"Full-Date (1004)" }
                                            option { value = "cbor_hex"; +"Raw CBOR Hex" }
                                            option { value = "raw_file"; +"Raw File" }
                                        }
                                    }
                                    td {
                                        css { padding = 6.px }
                                        if (elem.type == "raw_file") {
                                            div {
                                                css { display = Display.flex; gap = 8.px; alignItems = AlignItems.center }
                                                label {
                                                    css {
                                                        background = Color("#334155")
                                                        border = None.none
                                                        color = Color("#f1f5f9")
                                                        padding = Padding(4.px, 10.px)
                                                        borderRadius = 4.px
                                                        cursor = Cursor.pointer
                                                        fontSize = 12.px
                                                        hover { background = Color("#475569") }
                                                    }
                                                    +"📁 Choose File"
                                                    input {
                                                        type = "file".unsafeCast<InputType>()
                                                        css { display = None.none }
                                                        onChange = { event ->
                                                            val fileList = event.target.asDynamic().files
                                                            if (fileList != null && fileList.length > 0) {
                                                                val file = fileList[0].unsafeCast<File>()
                                                                val fName = file.name
                                                                val reader = FileReader()
                                                                reader.asDynamic().onload = {
                                                                    val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                                                    val bytes = Int8Array(arrayBuffer).toByteArray()
                                                                    namespaces = namespaces.mapIndexed { idx, item ->
                                                                        if (idx == nsIndex) {
                                                                            val updated = item.elements.toMutableList()
                                                                            updated[elemIndex] = elem.copy(fileBytes = bytes, fileName = fName)
                                                                            item.copy(elements = updated)
                                                                        } else item
                                                                    }
                                                                }
                                                                reader.readAsArrayBuffer(file)
                                                            }
                                                        }
                                                    }
                                                }

                                                if (elem.fileName.isNotEmpty()) {
                                                    span {
                                                        css { color = Color("#38bdf8"); fontSize = 12.px; fontFamily = "monospace".unsafeCast<FontFamily>() }
                                                        +"${elem.fileName} (${(elem.fileBytes?.size ?: 0) / 1024} KB)"
                                                    }
                                                } else {
                                                    span {
                                                        css { color = Color("#64748b"); fontSize = 12.px }
                                                        +"No file selected"
                                                    }
                                                }
                                            }
                                        } else {
                                            input {
                                                css {
                                                    width = 100.pct
                                                    padding = 4.px
                                                    borderRadius = 4.px
                                                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                    background = Color("#0f172a")
                                                    color = Color("#f8fafc")
                                                    fontFamily = "monospace".unsafeCast<FontFamily>()
                                                }
                                                value = elem.value
                                                onChange = { event ->
                                                    val newVal = event.target.asDynamic().value.unsafeCast<String>()
                                                    namespaces = namespaces.mapIndexed { idx, item ->
                                                        if (idx == nsIndex) {
                                                            val updated = item.elements.toMutableList()
                                                            updated[elemIndex] = elem.copy(value = newVal)
                                                            item.copy(elements = updated)
                                                        } else item
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    td {
                                        css { padding = 6.px; textAlign = TextAlign.center }
                                        button {
                                            css {
                                                background = Color("transparent")
                                                color = Color("#ef4444")
                                                border = None.none
                                                cursor = Cursor.pointer
                                                fontSize = 14.px
                                            }
                                            onClick = {
                                                namespaces = namespaces.mapIndexed { idx, item ->
                                                    if (idx == nsIndex) {
                                                        val updated = item.elements.toMutableList()
                                                        updated.removeAt(elemIndex)
                                                        item.copy(elements = updated)
                                                    } else item
                                                }
                                            }
                                            +"🗑️"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    button {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#f1f5f9")
                            padding = Padding(4.px, 10.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#475569") }
                        }
                        onClick = {
                            namespaces = namespaces.mapIndexed { idx, item ->
                                if (idx == nsIndex) {
                                    val updated = item.elements.toMutableList()
                                    updated.add(EditableDataElement(identifier = "new_element", type = "string", value = "value"))
                                    item.copy(elements = updated)
                                } else item
                            }
                        }
                        +"➕ Add Data Element"
                    }
                }
            }

            button {
                css {
                    background = Color("#2563eb")
                    color = Color("#ffffff")
                    border = None.none
                    padding = Padding(8.px, 16.px)
                    borderRadius = 6.px
                    cursor = Cursor.pointer
                    fontSize = 13.px
                    fontWeight = FontWeight.bold
                    hover { background = Color("#1d4ed8") }
                }
                onClick = {
                    namespaces = namespaces + EditableNamespace(name = "org.example.namespace", elements = mutableListOf())
                }
                +"➕ Add Namespace"
            }
        }

        // Section 5: Generation Action
        button {
            css {
                padding = Padding(14.px, 28.px)
                fontSize = 16.px
                fontWeight = FontWeight.bold
                backgroundColor = Color("#2563eb")
                color = Color("#ffffff")
                border = None.none
                borderRadius = 8.px
                cursor = Cursor.pointer
                transition = "all 0.2s".unsafeCast<Transition>()
                hover { backgroundColor = Color("#1d4ed8") }
                disabled { backgroundColor = Color("#475569"); cursor = Cursor.notAllowed }
            }
            disabled = isGenerating
            onClick = { createAndDownloadMpzPass() }
            if (isGenerating) +"Generating .mpzpass..." else +"✨ Generate & Download .mpzpass"
        }

        if (statusMessage.isNotEmpty()) {
            div {
                css {
                    marginTop = 20.px
                    padding = 16.px
                    borderRadius = 8.px
                    background = if (createdPassBytes != null) Color("#064e3b") else Color("#450a0a")
                    color = if (createdPassBytes != null) Color("#a7f3d0") else Color("#fca5a5")
                    border = Border(1.px, LineStyle.solid, if (createdPassBytes != null) Color("#059669") else Color("#991b1b"))
                    fontWeight = FontWeight.bold
                }
                +statusMessage
            }
        }
    }
}
