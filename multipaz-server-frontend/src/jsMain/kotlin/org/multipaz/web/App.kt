package org.multipaz.web

import emotion.react.css
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.toBase64Url
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.code
import react.useState
import web.cssom.*

private val scope = MainScope()

val App = FC<Props> {
    var hashResult by useState<String?>(null)
    var isComputing by useState(false)
    var error by useState<String?>(null)

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
            +"Hello, World!"
        }

        p {
            +"Welcome to Multipaz Kotlin/JS frontend that uses the multipaz library."
        }

        p {
            +"Click the button below to compute SHA-256 of \"hello, world!\" using "
            code { +"org.multipaz.crypto.Crypto" }
            +" and display the result as base64url."
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
                scope.launch {
                    isComputing = true
                    error = null
                    try {
                        val message = "hello, world!".encodeToByteArray()
                        val digest = Crypto.digest(Algorithm.SHA256, message)
                        hashResult = digest.toBase64Url()
                    } catch (e: Exception) {
                        error = e.message ?: "Unknown error"
                    } finally {
                        isComputing = false
                    }
                }
            }

            if (isComputing) {
                +"Computing..."
            } else {
                +"Compute SHA-256"
            }
        }

        hashResult?.let { result ->
            div {
                css {
                    marginTop = 24.px
                    padding = 16.px
                    backgroundColor = Color("#f0f0f0")
                    borderRadius = 6.px
                }

                p {
                    css {
                        margin = 0.px
                        fontWeight = FontWeight.bold
                    }
                    +"SHA-256 of \"hello, world!\" (base64url):"
                }

                code {
                    css {
                        display = Display.block
                        marginTop = 8.px
                        padding = 12.px
                        backgroundColor = Color("#ffffff")
                        borderRadius = 4.px
                        fontFamily = string("monospace")
                        wordBreak = WordBreak.breakAll
                    }
                    +result
                }
            }
        }

        error?.let { err ->
            div {
                css {
                    marginTop = 24.px
                    padding = 16.px
                    backgroundColor = Color("#ffeeee")
                    borderRadius = 6.px
                    color = Color("#cc0000")
                }
                +"Error: $err"
            }
        }

        div {
            css {
                marginTop = 48.px
                paddingTop = 24.px
                borderTop = Border(1.px, LineStyle.solid, Color("#eeeeee"))
                color = Color("#666666")
                fontSize = 14.px
            }
            +"Crypto provider: "
            code { +Crypto.provider }
        }
    }
}
