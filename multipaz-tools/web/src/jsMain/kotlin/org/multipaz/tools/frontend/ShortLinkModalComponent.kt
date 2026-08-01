package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.*

enum class ShortLinkStep {
    CONFIRM,
    LOADING,
    SUCCESS,
    ERROR
}

external interface ShortLinkModalProps : Props {
    var isOpen: Boolean
    var onClose: () -> Unit
    var targetPath: String
}

val ShortLinkModalComponent = FC<ShortLinkModalProps> { props ->
    var step by useState(ShortLinkStep.CONFIRM)
    var shortUrl by useState("")
    var errorMessage by useState("")
    var copied by useState(false)

    useEffect(props.isOpen) {
        if (props.isOpen) {
            step = ShortLinkStep.CONFIRM
            shortUrl = ""
            errorMessage = ""
            copied = false
        }
    }

    if (!props.isOpen) return@FC

    fun handleConfirm() {
        step = ShortLinkStep.LOADING
        errorMessage = ""

        mainScope.launch {
            try {
                val bodyJson = Json.encodeToString(
                    mapOf("path" to props.targetPath)
                )

                val reqInit = js("{}").unsafeCast<org.w3c.fetch.RequestInit>()
                reqInit.method = "POST"
                reqInit.headers = js("{'Content-Type': 'application/json'}")
                reqInit.body = bodyJson

                val response = window.fetch("/api/shorten", reqInit).await()
                val text = response.text().await()
                val json = Json.parseToJsonElement(text).jsonObject

                if (response.ok) {
                    val code = json["shortCode"]?.jsonPrimitive?.content ?: ""
                    shortUrl = "${window.location.origin}/s/$code"
                    step = ShortLinkStep.SUCCESS
                } else {
                    errorMessage = json["error"]?.jsonPrimitive?.content
                        ?: "Failed to create short link (HTTP ${response.status})"
                    step = ShortLinkStep.ERROR
                }
            } catch (e: Throwable) {
                errorMessage = e.message ?: "Network error creating short link"
                step = ShortLinkStep.ERROR
            }
        }
    }

    fun handleCopy() {
        window.asDynamic().navigator.clipboard.writeText(shortUrl)
        copied = true
        window.setTimeout({ copied = false }, 2000)
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
            backdropFilter = "blur(4px)".unsafeCast<BackdropFilter>()
            display = Display.flex
            alignItems = AlignItems.center
            justifyContent = JustifyContent.center
            zIndex = integer(9999)
        }
        onClick = { props.onClose() }

        // Modal Content Box
        div {
            css {
                background = Color("#1e293b")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 16.px
                padding = 32.px
                maxWidth = 500.px
                width = 90.pct
                boxShadow = BoxShadow(0.px, 20.px, 25.px, Color("rgba(0, 0, 0, 0.5)"))
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 16.px
            }
            onClick = { event -> event.stopPropagation() }

            when (step) {
                ShortLinkStep.CONFIRM -> {
                    h3 {
                        css {
                            fontSize = 1.4.rem
                            fontWeight = FontWeight.bold
                            color = Color("#f8fafc")
                            margin = Margin(0.px, 0.px, 4.px, 0.px)
                        }
                        +"🔗 Create Short Link?"
                    }

                    p {
                        css {
                            color = Color("#cbd5e1")
                            fontSize = 14.px
                            lineHeight = 1.5.em
                            margin = Margin(0.px, 0.px, 8.px, 0.px)
                        }
                        +"Creating a short link generates a compact URL that is much easier to share, send in messages, or reference in bug reports and documents."
                    }

                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#f59e0b"))
                            borderRadius = 8.px
                            padding = Padding(12.px, 16.px)
                            color = Color("#fbbf24")
                            fontSize = 13.px
                            lineHeight = 1.4.em
                        }
                        +"⚠️ Privacy Note: Processing normally happens client-side in your browser without sending data to the server. Creating a short link saves this URL on the web server so it can be resolved."
                    }

                    div {
                        css {
                            display = Display.flex
                            justifyContent = JustifyContent.end
                            gap = 12.px
                            marginTop = 12.px
                        }

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
                                hover {
                                    backgroundColor = Color("#475569")
                                }
                            }
                            onClick = { props.onClose() }
                            +"Cancel"
                        }

                        button {
                            css {
                                padding = Padding(10.px, 20.px)
                                fontSize = 14.px
                                fontWeight = FontWeight.bold
                                backgroundColor = Color("#3b82f6")
                                color = Color("#ffffff")
                                border = None.none
                                borderRadius = 8.px
                                cursor = Cursor.pointer
                                hover {
                                    backgroundColor = Color("#2563eb")
                                }
                            }
                            onClick = { handleConfirm() }
                            +"Confirm & Create Link"
                        }
                    }
                }

                ShortLinkStep.LOADING -> {
                    h3 {
                        css {
                            fontSize = 1.4.rem
                            fontWeight = FontWeight.bold
                            color = Color("#f8fafc")
                            margin = Margin(0.px, 0.px, 8.px, 0.px)
                        }
                        +"⏳ Generating Short Link..."
                    }
                    p {
                        css {
                            color = Color("#94a3b8")
                            fontSize = 14.px
                        }
                        +"Saving path payload to server..."
                    }
                }

                ShortLinkStep.SUCCESS -> {
                    h3 {
                        css {
                            fontSize = 1.4.rem
                            fontWeight = FontWeight.bold
                            color = Color("#34d399")
                            margin = Margin(0.px, 0.px, 4.px, 0.px)
                        }
                        +"✅ Short Link Ready"
                    }

                    p {
                        css {
                            color = Color("#cbd5e1")
                            fontSize = 14.px
                        }
                        +"Your short link has been created:"
                    }

                    div {
                        css {
                            display = Display.flex
                            gap = 8.px
                            alignItems = AlignItems.center
                        }

                        input {
                            css {
                                flex = number(1.0)
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                padding = 10.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                fontSize = 14.px
                            }
                            readOnly = true
                            value = shortUrl
                        }

                        button {
                            css {
                                padding = Padding(10.px, 16.px)
                                fontSize = 14.px
                                fontWeight = FontWeight.bold
                                backgroundColor = if (copied) Color("#059669") else Color("#3b82f6")
                                color = Color("#ffffff")
                                border = None.none
                                borderRadius = 8.px
                                cursor = Cursor.pointer
                                transition = "all 0.2s".unsafeCast<Transition>()
                            }
                            onClick = { handleCopy() }
                            +if (copied) "Copied!" else "📋 Copy"
                        }
                    }

                    div {
                        css {
                            display = Display.flex
                            justifyContent = JustifyContent.end
                            marginTop = 8.px
                        }

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
                                hover {
                                    backgroundColor = Color("#475569")
                                }
                            }
                            onClick = { props.onClose() }
                            +"Done"
                        }
                    }
                }

                ShortLinkStep.ERROR -> {
                    h3 {
                        css {
                            fontSize = 1.4.rem
                            fontWeight = FontWeight.bold
                            color = Color("#ef4444")
                            margin = Margin(0.px, 0.px, 4.px, 0.px)
                        }
                        +"❌ Unable to Create Short Link"
                    }

                    p {
                        css {
                            color = Color("#fca5a5")
                            fontSize = 14.px
                            lineHeight = 1.4.em
                        }
                        +errorMessage
                    }

                    div {
                        css {
                            display = Display.flex
                            justifyContent = JustifyContent.end
                            gap = 12.px
                            marginTop = 12.px
                        }

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
                                hover {
                                    backgroundColor = Color("#475569")
                                }
                            }
                            onClick = { props.onClose() }
                            +"Close"
                        }

                        button {
                            css {
                                padding = Padding(10.px, 20.px)
                                fontSize = 14.px
                                fontWeight = FontWeight.bold
                                backgroundColor = Color("#3b82f6")
                                color = Color("#ffffff")
                                border = None.none
                                borderRadius = 8.px
                                cursor = Cursor.pointer
                                hover {
                                    backgroundColor = Color("#2563eb")
                                }
                            }
                            onClick = { handleConfirm() }
                            +"Try Again"
                        }
                    }
                }
            }
        }
    }
}
