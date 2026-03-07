package org.multipaz.web.tools

import emotion.react.css
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import org.multipaz.util.toBase64
import org.multipaz.web.common.MultipazProps
import org.multipaz.web.common.mainScope
import react.FC
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.br
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.*

val ConverterApp = FC<MultipazProps> { props ->
    var certificate by useState("")
    var privateKey by useState("")
    var jwk by useState("")
    var isComputing by useState(false)

    div {
        css {
            fontFamily = FontFamily.sansSerif
            maxWidth = 800.px
            margin = Auto.auto
            padding = 40.px
        }

        h1 {
            css {
                color = Color("#333")
            }
            +"Convert PEM to JSON"
        }

        div {
            label {
                +"Certificate:"
            }
            br {}
            textarea {
                value = certificate
                placeholder = "PEM"
                onChange = { certificate = it.target.value }
                rows = 20
                cols = 80
            }
        }

        div {
            label {
                +"Private key:"
            }
            br {}
            textarea {
                value = privateKey
                placeholder = "PEM"
                onChange = { privateKey = it.target.value }
                rows = 6
                cols = 80
            }
        }

        button {
            css {
                padding = Padding(12.px, 24.px)
                fontSize = 16.px
                backgroundColor = Color("#0066cc")
                color = Color("#ffffff")
                border = None.none
                borderRadius = 6.px
                cursor = Cursor.pointer
                marginTop = 16.px
                disabled {
                    backgroundColor = Color("#cccccc")
                    cursor = Cursor.default
                }
            }

            disabled = isComputing

            onClick = {
                mainScope.launch {
                    isComputing = true
                    try {
                        val cert = X509Cert.fromPem(certificate)
                        val key = EcPrivateKey.fromPem(privateKey, cert.ecPublicKey)
                        val json = key.toJwk(buildJsonObject {
                            putJsonArray("x5c") {
                                add(cert.encoded.toByteArray().toBase64())
                            }
                        })
                        jwk = Json { prettyPrint = true }.encodeToString(json)
                    } catch (e: Exception) {
                        jwk = e.message ?: "Unknown error"
                    } finally {
                        isComputing = false
                    }
                }
            }

            if (isComputing) {
                +"Converting..."
            } else {
                +"Convert"
            }
        }

        div {
            label {
                +"JWK:"
            }
            br {}
            textarea {
                value = jwk
                placeholder = "JSON"
                onChange = { jwk = it.target.value }
                rows = 20
                cols = 80
            }
        }
    }
}
