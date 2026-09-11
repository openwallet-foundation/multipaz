package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.option
import react.useState
import web.cssom.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.RsaPrivateKey
import org.multipaz.crypto.MlDsaPrivateKey
import org.multipaz.crypto.MlKemPrivateKey
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.util.toHex

enum class KeyFamily(val displayName: String) {
    EC("Elliptic Curve (EC)"),
    RSA("RSA"),
    ML_DSA("ML-DSA (Post-Quantum Signature)"),
    ML_KEM("ML-KEM (Post-Quantum Key Encapsulation)")
}

val KeyGeneratorComponent = FC {
    var selectedFamily by useState(KeyFamily.EC)
    var selectedCurve by useState(EcCurve.P256)
    var selectedRsaKeySize by useState(2048)
    var selectedMlDsaAlgorithm by useState(Algorithm.ML_DSA_65)
    var selectedMlKemAlgorithm by useState(Algorithm.ML_KEM_768)
    var generatedPrivateKey by useState<PrivateKey?>(null)
    var isGenerating by useState(false)
    var privateKeyTab by useState("jwk")
    var publicKeyTab by useState("jwk")
    var copyPrivateKeySuccess by useState(false)
    var copyPublicKeySuccess by useState(false)
    var generateError by useState("")

    // Precomputed formats
    var jwkPrivateText by useState("")
    var jwkPublicText by useState("")
    var cosePrivateText by useState("")
    var cosePublicText by useState("")
    var diagPrivateText by useState("")
    var diagPublicText by useState("")
    var pemPrivateText by useState("")
    var pemPublicText by useState("")

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
            +"Key Pair Generator"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Generate secure Elliptic Curve, RSA, and Post-Quantum (ML-DSA / ML-KEM) key pairs directly in your browser."
        }

        div {
            css {
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 16.px
                marginBottom = 24.px
                maxWidth = 400.px
            }

            div {
                css {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 8.px
                }

                label {
                    css {
                        fontWeight = FontWeight.bold
                        color = Color("#cbd5e1")
                    }
                    +"Select Key Family:"
                }

                select {
                    css {
                        padding = 12.px
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 8.px
                        color = Color("#f1f5f9")
                        fontSize = 15.px
                    }
                    value = selectedFamily.name
                    onChange = {
                        val familyVal = KeyFamily.valueOf(it.target.value)
                        selectedFamily = familyVal
                    }
                    KeyFamily.entries.forEach { family ->
                        option {
                            value = family.name
                            +family.displayName
                        }
                    }
                }
            }

            when (selectedFamily) {
                KeyFamily.EC -> {
                    div {
                        css {
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }

                        label {
                            css {
                                fontWeight = FontWeight.bold
                                color = Color("#cbd5e1")
                            }
                            +"Select Elliptic Curve:"
                        }

                        select {
                            css {
                                padding = 12.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#f1f5f9")
                                fontSize = 15.px
                            }
                            value = selectedCurve.name
                            onChange = {
                                val curveVal = EcCurve.valueOf(it.target.value)
                                selectedCurve = curveVal
                            }
                            Crypto.supportedCurves.sortedBy { it.name }.forEach { curve ->
                                option {
                                    value = curve.name
                                    val labelText = when (curve) {
                                        EcCurve.P256 -> "P-256 (secp256r1)"
                                        EcCurve.P384 -> "P-384 (secp384r1)"
                                        EcCurve.P521 -> "P-521 (secp521r1)"
                                        EcCurve.ED25519 -> "Ed25519 (EdDSA)"
                                        EcCurve.X25519 -> "X25519 (ECDH)"
                                        EcCurve.ED448 -> "Ed448 (EdDSA)"
                                        EcCurve.X448 -> "X448 (ECDH)"
                                        else -> curve.name
                                    }
                                    +labelText
                                }
                            }
                        }
                    }
                }
                KeyFamily.RSA -> {
                    div {
                        css {
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }

                        label {
                            css {
                                fontWeight = FontWeight.bold
                                color = Color("#cbd5e1")
                            }
                            +"Select RSA Key Size:"
                        }

                        select {
                            css {
                                padding = 12.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#f1f5f9")
                                fontSize = 15.px
                            }
                            value = selectedRsaKeySize.toString()
                            onChange = {
                                selectedRsaKeySize = it.target.value.toInt()
                            }
                            listOf(2048, 3072, 4096).forEach { size ->
                                option {
                                    value = size.toString()
                                    +"$size bits"
                                }
                            }
                        }
                    }
                }
                KeyFamily.ML_DSA -> {
                    div {
                        css {
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }

                        label {
                            css {
                                fontWeight = FontWeight.bold
                                color = Color("#cbd5e1")
                            }
                            +"Select ML-DSA Parameter Set:"
                        }

                        select {
                            css {
                                padding = 12.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#f1f5f9")
                                fontSize = 15.px
                            }
                            value = selectedMlDsaAlgorithm.name
                            onChange = {
                                selectedMlDsaAlgorithm = Algorithm.valueOf(it.target.value)
                            }
                            listOf(
                                Algorithm.ML_DSA_44 to "ML-DSA-44 (NIST Security Category 2)",
                                Algorithm.ML_DSA_65 to "ML-DSA-65 (NIST Security Category 3)",
                                Algorithm.ML_DSA_87 to "ML-DSA-87 (NIST Security Category 5)"
                            ).forEach { (alg, labelText) ->
                                option {
                                    value = alg.name
                                    +labelText
                                }
                            }
                        }
                    }
                }
                KeyFamily.ML_KEM -> {
                    div {
                        css {
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }

                        label {
                            css {
                                fontWeight = FontWeight.bold
                                color = Color("#cbd5e1")
                            }
                            +"Select ML-KEM Parameter Set:"
                        }

                        select {
                            css {
                                padding = 12.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#f1f5f9")
                                fontSize = 15.px
                            }
                            value = selectedMlKemAlgorithm.name
                            onChange = {
                                selectedMlKemAlgorithm = Algorithm.valueOf(it.target.value)
                            }
                            listOf(
                                Algorithm.ML_KEM_512 to "ML-KEM-512 (NIST Security Category 1)",
                                Algorithm.ML_KEM_768 to "ML-KEM-768 (NIST Security Category 3)",
                                Algorithm.ML_KEM_1024 to "ML-KEM-1024 (NIST Security Category 5)"
                            ).forEach { (alg, labelText) ->
                                option {
                                    value = alg.name
                                    +labelText
                                }
                            }
                        }
                    }
                }
            }
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
                hover {
                    backgroundColor = Color("#2563eb")
                }
                disabled {
                    backgroundColor = Color("#475569")
                    cursor = Cursor.notAllowed
                }
            }
            disabled = isGenerating
            onClick = {
                mainScope.launch {
                    isGenerating = true
                    try {
                        generateError = ""
                        generatedPrivateKey = null
                        val key: PrivateKey = when (selectedFamily) {
                            KeyFamily.EC -> Crypto.createEcPrivateKey(selectedCurve)
                            KeyFamily.RSA -> Crypto.createRsaPrivateKey(selectedRsaKeySize)
                            KeyFamily.ML_DSA -> Crypto.createMlDsaPrivateKey(selectedMlDsaAlgorithm)
                            KeyFamily.ML_KEM -> Crypto.createMlKemPrivateKey(selectedMlKemAlgorithm)
                        }
                        generatedPrivateKey = key
                        
                        val jwkPrivate = key.toJwk()
                        jwkPrivateText = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), jwkPrivate)

                        val jwkPublic = key.publicKey.toJwk()
                        jwkPublicText = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), jwkPublic)

                        cosePrivateText = Cbor.encode(key.toCoseKey().toDataItem()).toHex()
                        cosePublicText = Cbor.encode(key.publicKey.toCoseKey().toDataItem()).toHex()

                        diagPrivateText = Cdn.encode(key.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)
                        diagPublicText = Cdn.encode(key.publicKey.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)

                        pemPrivateText = key.toPem()
                        pemPublicText = key.publicKey.toPem()

                        copyPrivateKeySuccess = false
                        copyPublicKeySuccess = false
                    } catch (e: Throwable) {
                        generateError = "Error generating key: " + (e.message ?: "Unsupported by current environment")
                    } finally {
                        isGenerating = false
                    }
                }
            }
            if (isGenerating) {
                +"Generating..."
            } else {
                +"Generate Key Pair"
            }
        }

        if (generateError.isNotEmpty()) {
            div {
                css {
                    marginTop = 16.px
                    color = Color("#ef4444")
                    fontWeight = FontWeight.bold
                    background = Color("#7f1d1d")
                    padding = Padding(10.px, 16.px)
                    borderRadius = 8.px
                    border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                }
                +generateError
            }
        }

        generatedPrivateKey?.let { privateKey ->
            div {
                css {
                    marginTop = 32.px
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 24.px
                }

                // Key Details Card
                div {
                    css {
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 12.px
                        padding = 20.px
                    }
                    h3 {
                        css {
                            margin = Margin(0.px, 0.px, 12.px, 0.px)
                            fontSize = 1.2.rem
                            color = Color("#f1f5f9")
                        }
                        +"Key Information"
                    }
                    div {
                        css {
                            display = Display.grid
                            gridTemplateColumns = "repeat(4, 1fr)".unsafeCast<GridTemplateColumns>()
                            gap = 16.px
                        }
                        div {
                            span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY FAMILY" }
                            span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold } ; +selectedFamily.displayName }
                        }
                        when (privateKey) {
                            is EcPrivateKey -> {
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CURVE NAME" }
                                    span { css { color = Color("#f1f5f9") } ; +privateKey.curve.name }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY SIZE" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.curve.bitSize} bits" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CAPABILITIES" }
                                    span {
                                        css { color = Color("#10b981") }
                                        val caps = mutableListOf<String>()
                                        if (privateKey.curve.supportsSigning) caps.add("Signing")
                                        if (privateKey.curve.supportsKeyAgreement) caps.add("Key Agreement")
                                        +caps.joinToString(", ")
                                    }
                                }
                            }
                            is RsaPrivateKey -> {
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY TYPE" }
                                    span { css { color = Color("#f1f5f9") } ; +"RSASSA / RSA-PSS" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY SIZE" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.publicKey.modulus.size * 8} bits" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CAPABILITIES" }
                                    span { css { color = Color("#10b981") } ; +"Signing, Encryption" }
                                }
                            }
                            is MlDsaPrivateKey -> {
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"ALGORITHM" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.algorithm.name} (FIPS 204)" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"PUBLIC KEY SIZE" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.publicKey.encoded.size} bytes" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CAPABILITIES" }
                                    span { css { color = Color("#10b981") } ; +"Post-Quantum Signing" }
                                }
                            }
                            is MlKemPrivateKey -> {
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"ALGORITHM" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.algorithm.name} (FIPS 203)" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"PUBLIC KEY SIZE" }
                                    span { css { color = Color("#f1f5f9") } ; +"${privateKey.publicKey.encoded.size} bytes" }
                                }
                                div {
                                    span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CAPABILITIES" }
                                    span { css { color = Color("#10b981") } ; +"Post-Quantum Key Encapsulation" }
                                }
                            }
                        }
                    }
                }

                // Grid layout for Private and Public keys side-by-side
                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(2, 1fr)".unsafeCast<GridTemplateColumns>()
                        gap = 24.px
                    }

                    // Private Key Section
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
                            }
                            +"Private Key"
                        }

                        // Formats Navbar
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
                                "cose" to "COSE Hex (CBOR)",
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

                        val privateKeyContent = when (privateKeyTab) {
                            "jwk" -> jwkPrivateText
                            "cose" -> cosePrivateText
                            "diagnostic" -> diagPrivateText
                            else -> pemPrivateText
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
                            value = privateKeyContent
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
                                window.navigator.asDynamic().clipboard.writeText(privateKeyContent)
                                copyPrivateKeySuccess = true
                            }
                            +(if (copyPrivateKeySuccess) "Copied!" else "Copy Private Key")
                        }
                    }

                    // Public Key Section
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
                            }
                            +"Public Key"
                        }

                        // Formats Navbar
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
                                "cose" to "COSE Hex (CBOR)",
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

                        val publicKeyContent = when (publicKeyTab) {
                            "jwk" -> jwkPublicText
                            "cose" -> cosePublicText
                            "diagnostic" -> diagPublicText
                            else -> pemPublicText
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
                            value = publicKeyContent
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
                                window.navigator.asDynamic().clipboard.writeText(publicKeyContent)
                                copyPublicKeySuccess = true
                            }
                            +(if (copyPublicKeySuccess) "Copied!" else "Copy Public Key")
                        }
                    }
                }
            }
        }
    }
}
