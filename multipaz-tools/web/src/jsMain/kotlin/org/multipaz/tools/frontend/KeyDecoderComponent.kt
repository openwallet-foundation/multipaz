@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffectOnce
import react.useState
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKeyOkp
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPrivateKeyOkp
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.RsaPrivateKey
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.MlDsaPrivateKey
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.crypto.MlKemPrivateKey
import org.multipaz.crypto.Algorithm
import kotlinx.io.bytestring.ByteString
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.toCoseLabel
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.util.toHex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ParsedKeyData(
    val privateKey: PrivateKey?,
    val publicKey: PublicKey,
    val jwkPub: String,
    val cosePub: String,
    val cdnPub: String,
    val pemPub: String,
    val jwkPriv: String,
    val cosePriv: String,
    val cdnPriv: String,
    val pemPriv: String
)

val KeyDecoderComponent = FC {
    var rawInput by useState("")
    var parsedKey by useState<ParsedKeyData?>(null)
    var parseError by useState("")
    var privateKeyTab by useState("jwk")
    var publicKeyTab by useState("jwk")
    var copyPrivateKeySuccess by useState(false)
    var copyPublicKeySuccess by useState(false)

    // PKCS#12 Import Modal State
    var pendingP12Bytes by useState<ByteArray?>(null)
    var isPassphraseModalOpen by useState(false)

    fun decodeKey(inputStr: String) {
        mainScope.launch {
            try {
                val cleanInput = inputStr.trim()
                if (cleanInput.isEmpty()) {
                    parsedKey = null
                    parseError = ""
                    return@launch
                }

                var privKey: PrivateKey? = null
                var pubKey: PublicKey? = null

                // 1. JWK JSON
                if (cleanInput.startsWith("{")) {
                    val jsonObj = Json.parseToJsonElement(cleanInput).jsonObject
                    if (jsonObj.containsKey("d") || jsonObj.containsKey("priv")) {
                        privKey = PrivateKey.fromJwk(jsonObj)
                        pubKey = privKey.publicKey
                    } else {
                        pubKey = PublicKey.fromJwk(jsonObj)
                    }
                }
                // 2. PEM Format
                else if (cleanInput.contains("-----BEGIN")) {
                    if (cleanInput.contains("PUBLIC KEY")) {
                        pubKey = PublicKey.fromPem(cleanInput)
                    } else {
                        try {
                            val parsedPair = parsePrivateKeyFromPem(cleanInput)
                            privKey = parsedPair.first
                            pubKey = parsedPair.second
                        } catch (e: Throwable) {
                            throw IllegalArgumentException("Failed to decode Private Key PEM: " + (e.message ?: "Invalid PEM"))
                        }
                    }
                }
                // 3. COSE Key / CBOR / CDN / Hex / Base64
                else {
                    val dataItem = try {
                        Cdn.parse(cleanInput)
                    } catch (e: Throwable) {
                        val bytes = decodeInputToBytes(cleanInput)
                        Cbor.decode(bytes)
                    }
                    val coseKey = CoseKey.fromDataItem(dataItem)
                    privKey = try {
                        coseKey.privateKey
                    } catch (e: Throwable) {
                        null
                    }
                    pubKey = privKey?.publicKey ?: coseKey.publicKey
                }

                val targetPubKey = pubKey ?: error("Failed to parse Public Key")

                val jwkPub = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), targetPubKey.toJwk())
                val cosePub = Cbor.encode(targetPubKey.toCoseKey().toDataItem()).toHex()
                val cdnPub = Cdn.encode(targetPubKey.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)
                val pemPub = targetPubKey.toPem()

                val jwkPriv = privKey?.let { Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it.toJwk()) } ?: ""
                val cosePriv = privKey?.let { Cbor.encode(it.toCoseKey().toDataItem()).toHex() } ?: ""
                val cdnPriv = privKey?.let { Cdn.encode(it.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty) } ?: ""
                val pemPriv = privKey?.let { it.toPem() } ?: ""

                parsedKey = ParsedKeyData(
                    privateKey = privKey,
                    publicKey = targetPubKey,
                    jwkPub = jwkPub,
                    cosePub = cosePub,
                    cdnPub = cdnPub,
                    pemPub = pemPub,
                    jwkPriv = jwkPriv,
                    cosePriv = cosePriv,
                    cdnPriv = cdnPriv,
                    pemPriv = pemPriv
                )
                parseError = ""
                updateUrlHashPayload(cleanInput)
            } catch (e: Throwable) {
                parseError = "Error decoding key: " + (e.message ?: "Unknown error")
                parsedKey = null
            }
        }
    }

    useEffectOnce {
        val hashPayload = getUrlHashPayload()
        if (hashPayload.isNotEmpty()) {
            rawInput = hashPayload
            decodeKey(hashPayload)
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
                margin = Margin(0.px, 0.px, 16.px, 0.px)
                color = Color("#f8fafc")
            }
            +"Key Decoder"
        }

        if (parsedKey != null || parseError.isNotEmpty()) {
            button {
                css {
                    padding = Padding(10.px, 20.px)
                    fontSize = 14.px
                    fontWeight = FontWeight.bold
                    backgroundColor = Color("#334155")
                    color = Color("#f1f5f9")
                    border = None.none
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    marginBottom = 24.px
                    hover {
                        backgroundColor = Color("#475569")
                    }
                }
                onClick = {
                    parsedKey = null
                    parseError = ""
                    updateUrlHashPayload("")
                }
                +"← Back to Input"
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
                        color = Color("#ef4444")
                        fontWeight = FontWeight.bold
                        background = Color("#7f1d1d")
                        padding = Padding(12.px, 16.px)
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                    }
                    +parseError
                }
            }

            parsedKey?.let { keyData ->
                val pubKey = keyData.publicKey
                val privKey = keyData.privateKey

                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    // Card 1: Key Metadata & Capabilities
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 16.px
                        }

                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b"))
                                paddingBottom = 12.px
                            }
                            h3 {
                                css {
                                    margin = 0.px
                                    fontSize = 1.3.rem
                                    color = Color("#f1f5f9")
                                }
                                +"Key Information"
                            }
                            span {
                                css {
                                    fontSize = 12.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(if (privKey != null) "#8b5cf6" else "#3b82f6")
                                    color = Color("#ffffff")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 20.px
                                }
                                +(if (privKey != null) "Private Key" else "Public Key")
                            }
                        }

                        div {
                            css {
                                display = Display.grid
                                gridTemplateColumns = "repeat(auto-fit, minmax(200px, 1fr))".unsafeCast<GridTemplateColumns>()
                                gap = 16.px
                            }

                            when (pubKey) {
                                is EcPublicKey -> {
                                    val curve = pubKey.curve
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY TYPE / CURVE" }
                                        span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 15.px }; +"EC (${curve.name})" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY SIZE" }
                                        span { css { color = Color("#f1f5f9"); fontSize = 15.px }; +"${curve.bitSize} bits" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"SIGNING" }
                                        span { css { color = Color(if (curve.supportsSigning) "#10b981" else "#ef4444"); fontSize = 15.px; fontWeight = FontWeight.bold }; +(if (curve.supportsSigning) "Supported" else "Not Supported") }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY AGREEMENT" }
                                        span { css { color = Color(if (curve.supportsKeyAgreement) "#10b981" else "#ef4444"); fontSize = 15.px; fontWeight = FontWeight.bold }; +(if (curve.supportsKeyAgreement) "Supported" else "Not Supported") }
                                    }
                                }
                                is RsaPublicKey -> {
                                    val bitSize = pubKey.modulus.size * 8
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY TYPE" }
                                        span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 15.px }; +"RSA" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY SIZE" }
                                        span { css { color = Color("#f1f5f9"); fontSize = 15.px }; +"$bitSize bits" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"SIGNING" }
                                        span { css { color = Color("#10b981"); fontSize = 15.px; fontWeight = FontWeight.bold }; +"Supported (RS/PS)" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY AGREEMENT" }
                                        span { css { color = Color("#ef4444"); fontSize = 15.px; fontWeight = FontWeight.bold }; +"Not Supported" }
                                    }
                                }
                                is MlDsaPublicKey -> {
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY TYPE / ALGORITHM" }
                                        span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 15.px }; +"ML-DSA (${pubKey.algorithm.name})" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"PUBLIC KEY SIZE" }
                                        span { css { color = Color("#f1f5f9"); fontSize = 15.px }; +"${pubKey.encoded.size} bytes" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"SIGNING" }
                                        span { css { color = Color("#10b981"); fontSize = 15.px; fontWeight = FontWeight.bold }; +"Supported (FIPS 204)" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"CATEGORY" }
                                        span {
                                            css { color = Color("#38bdf8"); fontSize = 15.px; fontWeight = FontWeight.bold }
                                            +when (pubKey.algorithm) {
                                                Algorithm.ML_DSA_44 -> "NIST Level 2 (Post-Quantum)"
                                                Algorithm.ML_DSA_65 -> "NIST Level 3 (Post-Quantum)"
                                                Algorithm.ML_DSA_87 -> "NIST Level 5 (Post-Quantum)"
                                                else -> "Post-Quantum"
                                            }
                                        }
                                    }
                                }
                                is MlKemPublicKey -> {
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY TYPE / ALGORITHM" }
                                        span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 15.px }; +"ML-KEM (${pubKey.algorithm.name})" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"PUBLIC KEY SIZE" }
                                        span { css { color = Color("#f1f5f9"); fontSize = 15.px }; +"${pubKey.encoded.size} bytes" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY ENCAPSULATION" }
                                        span { css { color = Color("#10b981"); fontSize = 15.px; fontWeight = FontWeight.bold }; +"Supported (FIPS 203)" }
                                    }
                                    div {
                                        span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"CATEGORY" }
                                        span {
                                            css { color = Color("#38bdf8"); fontSize = 15.px; fontWeight = FontWeight.bold }
                                            +when (pubKey.algorithm) {
                                                Algorithm.ML_KEM_512 -> "NIST Level 1 (Post-Quantum)"
                                                Algorithm.ML_KEM_768 -> "NIST Level 3 (Post-Quantum)"
                                                Algorithm.ML_KEM_1024 -> "NIST Level 5 (Post-Quantum)"
                                                else -> "Post-Quantum"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 2: Key Parameters & Coordinates
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 16.px
                        }

                        h3 {
                            css {
                                margin = 0.px
                                fontSize = 1.3.rem
                                color = Color("#f1f5f9")
                                borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b"))
                                paddingBottom = 12.px
                            }
                            +"Key Parameters & Coordinates"
                        }

                        when (pubKey) {
                            is EcPublicKey -> {
                                val coordsString = when (pubKey) {
                                    is EcPublicKeyDoubleCoordinate -> "X: ${pubKey.x.toHex()}\nY: ${pubKey.y.toHex()}"
                                    is EcPublicKeyOkp -> "X: ${pubKey.x.toHex()}"
                                }

                                div {
                                    span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Public Key Coordinates" }
                                    pre {
                                        css {
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#38bdf8")
                                            marginTop = 8.px
                                            overflowX = "auto".unsafeCast<Overflow>()
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +coordsString
                                    }
                                }

                                if (privKey is EcPrivateKey) {
                                    val dString = "d: ${privKey.d.toHex()}"
                                    div {
                                        span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Private Key Parameter (d)" }
                                        pre {
                                            css {
                                                background = Color("#1e293b")
                                                padding = 16.px
                                                borderRadius = 8.px
                                                fontFamily = FontFamily.monospace
                                                fontSize = 12.px
                                                color = Color("#34d399")
                                                marginTop = 8.px
                                                overflowX = "auto".unsafeCast<Overflow>()
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            +dString
                                        }
                                    }
                                }
                            }
                            is RsaPublicKey -> {
                                val nString = "n (Modulus, ${pubKey.modulus.size * 8} bits):\n${pubKey.modulus.toHex()}"
                                val eString = "e (Public Exponent):\n${pubKey.publicExponent.toHex()}"
                                div {
                                    span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Public Modulus (n)" }
                                    pre {
                                        css {
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#38bdf8")
                                            marginTop = 8.px
                                            overflowX = "auto".unsafeCast<Overflow>()
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +nString
                                    }
                                }
                                div {
                                    span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Public Exponent (e)" }
                                    pre {
                                        css {
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#38bdf8")
                                            marginTop = 8.px
                                            overflowX = "auto".unsafeCast<Overflow>()
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +eString
                                    }
                                }
                                if (privKey is RsaPrivateKey) {
                                    val dString = "d (Private Exponent):\n${privKey.privateExponent.toHex()}"
                                    div {
                                        span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Private Exponent (d)" }
                                        pre {
                                            css {
                                                background = Color("#1e293b")
                                                padding = 16.px
                                                borderRadius = 8.px
                                                fontFamily = FontFamily.monospace
                                                fontSize = 12.px
                                                color = Color("#34d399")
                                                marginTop = 8.px
                                                overflowX = "auto".unsafeCast<Overflow>()
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            +dString
                                        }
                                    }
                                    val pBytes = privKey.p
                                    val qBytes = privKey.q
                                    if (pBytes != null && qBytes != null) {
                                        val primesString = "p:\n${pBytes.toHex()}\n\nq:\n${qBytes.toHex()}"
                                        div {
                                            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Prime Factors (p, q)" }
                                            pre {
                                                css {
                                                    background = Color("#1e293b")
                                                    padding = 16.px
                                                    borderRadius = 8.px
                                                    fontFamily = FontFamily.monospace
                                                    fontSize = 12.px
                                                    color = Color("#34d399")
                                                    marginTop = 8.px
                                                    overflowX = "auto".unsafeCast<Overflow>()
                                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                }
                                                +primesString
                                            }
                                        }
                                    }
                                }
                            }
                            is MlDsaPublicKey -> {
                                div {
                                    span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Public Key (pk, ${pubKey.encoded.size} bytes)" }
                                    pre {
                                        css {
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#38bdf8")
                                            marginTop = 8.px
                                            overflowX = "auto".unsafeCast<Overflow>()
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +pubKey.encoded.toHex()
                                    }
                                }
                                if (privKey is MlDsaPrivateKey) {
                                    div {
                                        span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Private Key (sk, ${privKey.encoded.size} bytes)" }
                                        pre {
                                            css {
                                                background = Color("#1e293b")
                                                padding = 16.px
                                                borderRadius = 8.px
                                                fontFamily = FontFamily.monospace
                                                fontSize = 12.px
                                                color = Color("#34d399")
                                                marginTop = 8.px
                                                overflowX = "auto".unsafeCast<Overflow>()
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            +privKey.encoded.toHex()
                                        }
                                    }
                                }
                            }
                            is MlKemPublicKey -> {
                                div {
                                    span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Encapsulation Key (ek, ${pubKey.encoded.size} bytes)" }
                                    pre {
                                        css {
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#38bdf8")
                                            marginTop = 8.px
                                            overflowX = "auto".unsafeCast<Overflow>()
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +pubKey.encoded.toHex()
                                    }
                                }
                                if (privKey is MlKemPrivateKey) {
                                    div {
                                        span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Decapsulation Key (dk, ${privKey.encoded.size} bytes)" }
                                        pre {
                                            css {
                                                background = Color("#1e293b")
                                                padding = 16.px
                                                borderRadius = 8.px
                                                fontFamily = FontFamily.monospace
                                                fontSize = 12.px
                                                color = Color("#34d399")
                                                marginTop = 8.px
                                                overflowX = "auto".unsafeCast<Overflow>()
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            +privKey.encoded.toHex()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 3 & 4: Encoded Representations (Public & Private Keys)
                    div {
                        css {
                            display = Display.grid
                            gridTemplateColumns = (if (privKey != null) "repeat(2, 1fr)" else "1fr").unsafeCast<GridTemplateColumns>()
                            gap = 24.px
                        }

                        // Public Key Representations
                        div {
                            css {
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 12.px
                                padding = 24.px
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 16.px
                            }
                            h3 {
                                css { margin = 0.px; fontSize = 1.3.rem; color = Color("#f1f5f9") }
                                +"Public Key Representations"
                            }

                            div {
                                css {
                                    display = Display.flex
                                    gap = 8.px
                                    background = Color("#1e293b")
                                    padding = 4.px
                                    borderRadius = 8.px
                                }
                                listOf(
                                    "jwk" to "JWK (JSON)",
                                    "cose" to "COSE Hex",
                                    "diagnostic" to "CDN",
                                    "pem" to "PEM"
                                ).forEach { (tabId, tabTitle) ->
                                    button {
                                        css {
                                            padding = Padding(6.px, 12.px)
                                            border = None.none
                                            borderRadius = 6.px
                                            fontSize = 12.px
                                            fontWeight = FontWeight.bold
                                            cursor = Cursor.pointer
                                            if (publicKeyTab == tabId) {
                                                background = Color("#3b82f6")
                                                color = Color("#ffffff")
                                            } else {
                                                background = Color("transparent")
                                                color = Color("#94a3b8")
                                                hover { color = Color("#f1f5f9") }
                                            }
                                        }
                                        onClick = { publicKeyTab = tabId; copyPublicKeySuccess = false }
                                        +tabTitle
                                    }
                                }
                            }

                            val pubContent = when (publicKeyTab) {
                                "jwk" -> keyData.jwkPub
                                "cose" -> keyData.cosePub
                                "diagnostic" -> keyData.cdnPub
                                else -> keyData.pemPub
                            }

                            textarea {
                                css {
                                    width = 100.pct
                                    height = 240.px
                                    background = Color("#1e293b")
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    borderRadius = 8.px
                                    color = Color("#38bdf8")
                                    fontFamily = FontFamily.monospace
                                    fontSize = 12.px
                                    padding = 12.px
                                    resize = "none".unsafeCast<Resize>()
                                    focus { outline = None.none }
                                }
                                readOnly = true
                                value = pubContent
                            }

                            button {
                                css {
                                    padding = Padding(10.px, 20.px)
                                    fontSize = 14.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(if (copyPublicKeySuccess) "#10b981" else "#334155")
                                    color = Color("#ffffff")
                                    border = None.none
                                    borderRadius = 8.px
                                    cursor = Cursor.pointer
                                    hover {
                                        backgroundColor = Color(if (copyPublicKeySuccess) "#10b981" else "#475569")
                                    }
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(pubContent)
                                    copyPublicKeySuccess = true
                                }
                                +(if (copyPublicKeySuccess) "Copied!" else "Copy Public Key")
                            }
                        }

                        // Private Key Representations (if present)
                        if (privKey != null) {
                            div {
                                css {
                                    background = Color("#0f172a")
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    borderRadius = 12.px
                                    padding = 24.px
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    gap = 16.px
                                }
                                h3 {
                                    css { margin = 0.px; fontSize = 1.3.rem; color = Color("#f1f5f9") }
                                    +"Private Key Representations"
                                }

                                div {
                                    css {
                                        display = Display.flex
                                        gap = 8.px
                                        background = Color("#1e293b")
                                        padding = 4.px
                                        borderRadius = 8.px
                                    }
                                    listOf(
                                        "jwk" to "JWK (JSON)",
                                        "cose" to "COSE Hex",
                                        "diagnostic" to "CDN",
                                        "pem" to "PEM"
                                    ).forEach { (tabId, tabTitle) ->
                                        button {
                                            css {
                                                padding = Padding(6.px, 12.px)
                                                border = None.none
                                                borderRadius = 6.px
                                                fontSize = 12.px
                                                fontWeight = FontWeight.bold
                                                cursor = Cursor.pointer
                                                if (privateKeyTab == tabId) {
                                                    background = Color("#3b82f6")
                                                    color = Color("#ffffff")
                                                } else {
                                                    background = Color("transparent")
                                                    color = Color("#94a3b8")
                                                    hover { color = Color("#f1f5f9") }
                                                }
                                            }
                                            onClick = { privateKeyTab = tabId; copyPrivateKeySuccess = false }
                                            +tabTitle
                                        }
                                    }
                                }

                                val privContent = when (privateKeyTab) {
                                    "jwk" -> keyData.jwkPriv
                                    "cose" -> keyData.cosePriv
                                    "diagnostic" -> keyData.cdnPriv
                                    else -> keyData.pemPriv
                                }

                                textarea {
                                    css {
                                        width = 100.pct
                                        height = 240.px
                                        background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        borderRadius = 8.px
                                        color = Color("#34d399")
                                        fontFamily = FontFamily.monospace
                                        fontSize = 12.px
                                        padding = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                        focus { outline = None.none }
                                    }
                                    readOnly = true
                                    value = privContent
                                }

                                button {
                                    css {
                                        padding = Padding(10.px, 20.px)
                                        fontSize = 14.px
                                        fontWeight = FontWeight.bold
                                        backgroundColor = Color(if (copyPrivateKeySuccess) "#10b981" else "#334155")
                                        color = Color("#ffffff")
                                        border = None.none
                                        borderRadius = 8.px
                                        cursor = Cursor.pointer
                                        hover {
                                            backgroundColor = Color(if (copyPrivateKeySuccess) "#10b981" else "#475569")
                                        }
                                    }
                                    onClick = {
                                        window.navigator.asDynamic().clipboard.writeText(privContent)
                                        copyPrivateKeySuccess = true
                                    }
                                    +(if (copyPrivateKeySuccess) "Copied!" else "Copy Private Key")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Decode and inspect EC, RSA, ML-DSA, and ML-KEM public and private keys. Supports JWK (JSON), COSE Key (CBOR Hex/Base64/CDN), and PEM formats."
            }

            div {
                css {
                    display = Display.flex
                    justifyContent = JustifyContent.spaceBetween
                    alignItems = AlignItems.center
                    marginBottom = 8.px
                }

                label {
                    css {
                        fontWeight = FontWeight.bold
                        color = Color("#cbd5e1")
                    }
                    +"Public or Private Key (JWK, COSE CBOR/CDN, or PEM):"
                }

                div {
                    css {
                        display = Display.flex
                        gap = 8.px
                        alignItems = AlignItems.center
                    }

                    if (rawInput.isNotEmpty()) {
                        button {
                            css {
                                background = Color("#334155")
                                border = None.none
                                color = Color("#f1f5f9")
                                padding = Padding(4.px, 12.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontSize = 13.px
                                hover { background = Color("#475569") }
                            }
                            onClick = {
                                rawInput = ""
                                parsedKey = null
                                parseError = ""
                                updateUrlHashPayload("")
                            }
                            +"🗑️ Clear"
                        }
                    }

                    label {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#f1f5f9")
                            padding = Padding(4.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 13.px
                            hover { background = Color("#475569") }
                        }
                        +"📁 Load data"
                        input {
                            type = "file".unsafeCast<InputType>()
                            accept = ".json,.pem,.cbor,.hex,.p12,.pfx,.txt,*/*"
                            css { display = None.none }
                            onChange = { event ->
                                val fileList = event.target.asDynamic().files
                                if (fileList != null && fileList.length > 0) {
                                    val file = fileList[0].unsafeCast<File>()
                                    if (file.name.endsWith(".p12", ignoreCase = true) || file.name.endsWith(".pfx", ignoreCase = true)) {
                                        loadPkcs12File(
                                            file = file,
                                            onLoaded = { p12 ->
                                                val pem = p12.privateKey.toPem()
                                                rawInput = pem
                                                decodeKey(pem)
                                            },
                                            onNeedPassphrase = { bytes ->
                                                pendingP12Bytes = bytes
                                                isPassphraseModalOpen = true
                                            },
                                            onError = { parseError = it }
                                        )
                                    } else {
                                        val reader = FileReader()
                                        reader.asDynamic().onload = {
                                            val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                            val bytes = Int8Array(arrayBuffer).toByteArray()
                                            val text = bytes.decodeToString()
                                            if (text.startsWith("{") || text.contains("-----BEGIN") || text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it.isWhitespace() }) {
                                                rawInput = text.trim()
                                            } else {
                                                rawInput = bytes.toHex()
                                            }
                                        }
                                        reader.readAsArrayBuffer(file)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            textarea {
                css {
                    width = 100.pct
                    height = 200.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#f1f5f9")
                    fontFamily = FontFamily.monospace
                    padding = 12.px
                    resize = "none".unsafeCast<Resize>()
                    marginBottom = 4.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste key here (JWK JSON, COSE Key CBOR/CDN, or PEM format)..."
                onChange = { rawInput = it.target.value }
            }

            DetectedInputBadge {
                input = rawInput
            }

            button {
                css {
                    padding = Padding(12.px, 24.px)
                    fontSize = 16.px
                    fontWeight = FontWeight.bold
                    backgroundColor = Color("#3b82f6")
                    color = Color("#ffffff")
                    border = None.none
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    transition = "all 0.2s".unsafeCast<Transition>()
                    hover { backgroundColor = Color("#2563eb") }
                    disabled {
                        backgroundColor = Color("#475569")
                        cursor = Cursor.notAllowed
                    }
                }
                disabled = rawInput.trim().isEmpty()
                onClick = { decodeKey(rawInput) }
                +"Decode Key"
            }
        }

        Pkcs12PassphraseModalComponent {
            isOpen = isPassphraseModalOpen
            onClose = { isPassphraseModalOpen = false }
            rawBytes = pendingP12Bytes
            onDecoded = { p12 ->
                val pem = p12.privateKey.toPem()
                rawInput = pem
                decodeKey(pem)
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun parsePrivateKeyFromPem(pemStr: String): Pair<PrivateKey, PublicKey> {
    val trimmed = pemStr.trim()
    if (trimmed.contains("-----BEGIN RSA PRIVATE KEY-----")) {
        val privKey = RsaPrivateKey.fromPem(trimmed)
        return Pair(privKey, privKey.publicKey)
    }

    val b64 = trimmed
        .lines()
        .filterNot { it.startsWith("-----") || it.startsWith("#") }
        .joinToString("")
        .replace(Regex("[\\s\\r\\n\\t]"), "")
    val bytes = Base64.Mime.decode(b64)
    val rootObj = ASN1.decode(bytes) as ASN1Sequence

    val privateKeyAlgorithm = (if (rootObj.elements.size > 1 && rootObj.elements[1] is ASN1Sequence) rootObj.elements[1] else null) as? ASN1Sequence
    val algorithmOid = (privateKeyAlgorithm?.elements?.getOrNull(0) as? ASN1ObjectIdentifier)?.oid

    if (algorithmOid == OID.RSA_ENCRYPTION.oid) {
        val privKey = RsaPrivateKey.fromPem(trimmed)
        return Pair(privKey, privKey.publicKey)
    }

    val privateKeySeq = if (rootObj.elements.size > 2 && rootObj.elements[2] is ASN1OctetString) {
        val octetString = rootObj.elements[2] as ASN1OctetString
        try {
            ASN1.decode(octetString.value) as? ASN1Sequence ?: rootObj
        } catch (e: Throwable) {
            rootObj
        }
    } else {
        rootObj
    }

    var publicKeyBitString: ByteArray? = null
    for (element in (rootObj.elements + privateKeySeq.elements)) {
        if (element is ASN1TaggedObject && element.tag == 1) {
            val decodedBitString = try { ASN1.decode(element.content) } catch (e: Throwable) { null }
            if (decodedBitString is ASN1BitString) {
                publicKeyBitString = decodedBitString.value
            } else if (decodedBitString is ASN1OctetString) {
                publicKeyBitString = decodedBitString.value
            } else {
                publicKeyBitString = element.content
            }
            break
        }
    }

    if (algorithmOid == OID.EC_PUBLIC_KEY.oid || algorithmOid?.startsWith("1.3.101.") == true || trimmed.contains("EC PRIVATE KEY")) {
        val curve = when (algorithmOid) {
            OID.EC_PUBLIC_KEY.oid -> {
                val ecCurveString = privateKeyAlgorithm?.elements?.getOrNull(1) as? ASN1ObjectIdentifier
                when (ecCurveString?.oid) {
                    "1.2.840.10045.3.1.7" -> EcCurve.P256
                    "1.3.132.0.34" -> EcCurve.P384
                    "1.3.132.0.35" -> EcCurve.P521
                    "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                    "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                    "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                    "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                    else -> throw IllegalStateException("Unexpected curve OID ${ecCurveString?.oid}")
                }
            }
            "1.3.101.110" -> EcCurve.X25519
            "1.3.101.111" -> EcCurve.X448
            "1.3.101.112" -> EcCurve.ED25519
            "1.3.101.113" -> EcCurve.ED448
            else -> throw IllegalStateException("Unexpected OID $algorithmOid")
        }

        if (publicKeyBitString == null) {
            throw IllegalArgumentException("The EC private key PEM does not contain the optional public key structure. Please use JWK format or a PEM containing the public key.")
        }

        val pubKey = when (curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, publicKeyBitString)
            }
            EcCurve.ED25519,
            EcCurve.X25519,
            EcCurve.ED448,
            EcCurve.X448 -> {
                EcPublicKeyOkp(curve, publicKeyBitString)
            }
        }

        val privKey = EcPrivateKey.fromPem(trimmed, pubKey)
        return Pair(privKey, pubKey)
    }

    if (algorithmOid in listOf(OID.ML_DSA_44.oid, OID.ML_DSA_65.oid, OID.ML_DSA_87.oid)) {
        val alg = when (algorithmOid) {
            OID.ML_DSA_44.oid -> Algorithm.ML_DSA_44
            OID.ML_DSA_65.oid -> Algorithm.ML_DSA_65
            OID.ML_DSA_87.oid -> Algorithm.ML_DSA_87
            else -> throw IllegalStateException()
        }
        if (publicKeyBitString == null) {
            throw IllegalArgumentException("The ML-DSA private key PEM does not contain the public key. Please use JWK format.")
        }
        val pubKey = MlDsaPublicKey(alg, ByteString(publicKeyBitString))
        val privKey = MlDsaPrivateKey.fromPem(trimmed, pubKey)
        return Pair(privKey, pubKey)
    }

    if (algorithmOid in listOf(OID.ML_KEM_512.oid, OID.ML_KEM_768.oid, OID.ML_KEM_1024.oid)) {
        val alg = when (algorithmOid) {
            OID.ML_KEM_512.oid -> Algorithm.ML_KEM_512
            OID.ML_KEM_768.oid -> Algorithm.ML_KEM_768
            OID.ML_KEM_1024.oid -> Algorithm.ML_KEM_1024
            else -> throw IllegalStateException()
        }
        if (publicKeyBitString == null) {
            throw IllegalArgumentException("The ML-KEM private key PEM does not contain the public key. Please use JWK format.")
        }
        val pubKey = MlKemPublicKey(alg, ByteString(publicKeyBitString))
        val privKey = MlKemPrivateKey.fromPem(trimmed, pubKey)
        return Pair(privKey, pubKey)
    }

    throw IllegalArgumentException("Unsupported private key format or algorithm: $algorithmOid")
}
