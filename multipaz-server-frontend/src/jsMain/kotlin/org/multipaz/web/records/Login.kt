package org.multipaz.web.records

import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.web.common.jsonPost
import org.multipaz.web.common.mainScope
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.button
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.document
import web.html.InputType
import web.html.password

external interface LoginProps : Props {
    var value: Boolean
    var serviceUrl: String
    var onChange: (loggedIn: Boolean) -> Unit
}

val Login = FC<LoginProps> { props ->
    var error by useState<String?>(null)
    var password by useState("")

    useEffect(true) {
        val result = jsonPost("${props.serviceUrl}/identity/auth_check") {}.jsonObject
        val success = result["status"]?.jsonPrimitive?.content == "success"
        if (success != props.value) {
            props.onChange(success)
        }
    }

    div {
        className = ClassName("records-login")
        if (error == null) {
            div {
                className = ClassName("records-login-error")
                +error
            }
        }
        if (props.value) {
            button {
                +"Logout"
                onClick = {
                    document.cookie =
                        "records_admin_auth=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;"
                    props.onChange(false)
                }
            }
        } else {
            span {
                className = ClassName("records-login-label")
                +"Admin password:"
            }
            input {
                className = ClassName("records-login-password")
                type = InputType.password
                value = password
                onChange = { e ->
                    password = e.target.value
                }
            }
            +" "
            button {
                +"Login"
                onClick = {
                    mainScope.launch {
                        val result = jsonPost("${props.serviceUrl}/identity/auth") {
                            put("password", password)
                        }.jsonObject
                        if (result.containsKey("error")) {
                            error = result["error"]!!.jsonPrimitive.content
                        } else {
                            val cookie = result["cookie"]?.jsonPrimitive?.content
                            val expiresIn = result["expires_in"]?.jsonPrimitive?.content
                            document.cookie =
                                "records_admin_auth=$cookie; max_age=$expiresIn; path=/;"
                            props.onChange(true)
                        }
                    }
                }
            }
        }
    }
}