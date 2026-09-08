package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Pkcs12
import org.multipaz.crypto.Pkcs12WrongPassphraseException
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.*
import web.html.InputType

external interface Pkcs12PassphraseModalProps : Props {
    var isOpen: Boolean
    var onClose: () -> Unit
    var rawBytes: ByteArray?
    var onDecoded: (p12: Pkcs12) -> Unit
}

val Pkcs12PassphraseModalComponent = FC<Pkcs12PassphraseModalProps> { props ->
    var passphrase by useState("")
    var errorMessage by useState("")
    var isDecoding by useState(false)

    useEffect(props.isOpen) {
        if (props.isOpen) {
            passphrase = ""
            errorMessage = ""
            isDecoding = false
        }
    }

    if (!props.isOpen) return@FC

    fun handleUnlock() {
        val bytes = props.rawBytes
        if (bytes == null || bytes.isEmpty()) {
            errorMessage = "No file data to decode."
            return
        }

        isDecoding = true
        errorMessage = ""

        mainScope.launch {
            try {
                val p12 = Pkcs12.fromDer(ByteString(bytes), passphrase)
                props.onDecoded(p12)
                props.onClose()
            } catch (e: Pkcs12WrongPassphraseException) {
                errorMessage = "Incorrect passphrase. Please check and try again."
            } catch (e: Throwable) {
                errorMessage = "Failed to unlock PKCS#12: ${e.message ?: e.toString()}"
            } finally {
                isDecoding = false
            }
        }
    }

    // Modal Backdrop
    div {
        css {
            position = Position.fixed
            top = 0.px
            left = 0.px
            right = 0.px
            bottom = 0.px
            backgroundColor = Color("rgba(15, 23, 42, 0.75)")
            display = Display.flex
            alignItems = AlignItems.center
            justifyContent = JustifyContent.center
            zIndex = integer(1000)
            backdropFilter = blur(4.px)
        }
        onClick = { props.onClose() }

        // Modal Content
        div {
            css {
                backgroundColor = Color("#1e293b")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 16.px
                padding = 24.px
                maxWidth = 440.px
                width = 90.pct
                boxShadow = BoxShadow(0.px, 20.px, 25.px, (-5).px, Color("rgba(0, 0, 0, 0.5)"))
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 16.px
            }
            onClick = { it.stopPropagation() }

            h3 {
                css {
                    margin = Margin(0.px, 0.px, 4.px, 0.px)
                    fontSize = 1.3.rem
                    fontWeight = FontWeight.bold
                    color = Color("#f8fafc")
                }
                +"🔒 Encrypted PKCS#12 File"
            }

            p {
                css {
                    margin = 0.px
                    fontSize = 14.px
                    color = Color("#94a3b8")
                    lineHeight = 1.4.em
                }
                +"This PKCS#12 (.p12 / .pfx) container is protected by a passphrase. Enter the passphrase to unlock and import the certificate and private key."
            }

            // Passphrase Input
            div {
                css {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 6.px
                }
                label {
                    css {
                        fontSize = 13.px
                        fontWeight = FontWeight.bold
                        color = Color("#cbd5e1")
                    }
                    +"Passphrase"
                }
                input {
                    type = "password".unsafeCast<InputType>()
                    value = passphrase
                    placeholder = "Enter passphrase..."
                    autoFocus = true
                    css {
                        padding = Padding(10.px, 12.px)
                        backgroundColor = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 8.px
                        color = Color("#f8fafc")
                        fontSize = 14.px
                        outline = None.none
                        focus { borderColor = Color("#3b82f6") }
                    }
                    onChange = { passphrase = it.target.value }
                    onKeyDown = { event ->
                        if (event.key == "Enter" && !isDecoding) {
                            handleUnlock()
                        }
                    }
                }
            }

            // Error Banner
            if (errorMessage.isNotEmpty()) {
                div {
                    css {
                        padding = Padding(10.px, 12.px)
                        backgroundColor = Color("#451a1a")
                        border = Border(1.px, LineStyle.solid, Color("#7f1d1d"))
                        borderRadius = 8.px
                        color = Color("#fca5a5")
                        fontSize = 13.px
                    }
                    +errorMessage
                }
            }

            // Action Buttons
            div {
                css {
                    display = Display.flex
                    justifyContent = JustifyContent.flexEnd
                    gap = 10.px
                    marginTop = 8.px
                }

                button {
                    css {
                        padding = Padding(8.px, 16.px)
                        backgroundColor = Color("#334155")
                        color = Color("#f1f5f9")
                        border = None.none
                        borderRadius = 8.px
                        fontSize = 14.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        hover { backgroundColor = Color("#475569") }
                    }
                    onClick = { props.onClose() }
                    +"Cancel"
                }

                button {
                    css {
                        padding = Padding(8.px, 18.px)
                        backgroundColor = Color("#3b82f6")
                        color = Color("#ffffff")
                        border = None.none
                        borderRadius = 8.px
                        fontSize = 14.px
                        fontWeight = FontWeight.bold
                        cursor = if (isDecoding) Cursor.notAllowed else Cursor.pointer
                        opacity = if (isDecoding) number(0.6) else number(1.0)
                        hover { if (!isDecoding) backgroundColor = Color("#2563eb") }
                    }
                    disabled = isDecoding
                    onClick = { handleUnlock() }
                    if (isDecoding) +"Unlocking..." else +"Unlock & Import"
                }
            }
        }
    }
}
