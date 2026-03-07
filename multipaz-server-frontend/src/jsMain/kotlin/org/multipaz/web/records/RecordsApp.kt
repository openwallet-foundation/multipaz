package org.multipaz.web.records

import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.web.common.MultipazProps
import org.multipaz.web.common.jsonPost
import org.multipaz.web.common.resolveUrl
import react.FC
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.window.window

/**
 * Renders the screen with the list of all identities in the System of Record.
 */
val RecordsApp = FC<MultipazProps> { props ->
    val serviceUrl = resolveUrl(props.definition.getAttribute("data-service-url") ?: "records")
    var persons by useState<List<PersonBasicData>>(emptyList())
    var loggedIn by useState(false)
    var metadata by useState<RecordsMetadata?>(null)

    useEffect(true) {
        persons = fetchIdentityList(serviceUrl)
    }

    useEffect(true) {
        metadata = fetchRecordsMetadata(serviceUrl)
    }

    Login {
        value = loggedIn
        this.serviceUrl = serviceUrl
        onChange = { loggedIn = it }
    }

    metadata?.names?.let { names ->
        h1 {
            +names.state
        }
        h2 {
            +"Registry"
        }
        p {
            +"""You are playing the role of ${names.official} of ${names.state}. ${names.state}
            is a fictional state, of course, and all individuals here are fictional as well. This
            page contains the list of individuals registered in ${names.state}.
            An individual is uniquely identified by "Utopia id" which is assigned automatically when
            an individual is first entered in the system. Click on "Utopia id" to review the
            information on the given individual. Digital identities issued by
            ${names.state} are based on the information entered in this system.
            """
        }
    }

    button {
        className = ClassName("records-list-add")
        +"Add an individual"
        onClick = {
            window.location.href = "person.html"
        }
    }

    table {
        className = ClassName("records-table")
        tbody {
            tr {
                className = ClassName("records-table-header")
                th { + "Last name" }
                th { + "First name" }
                th { + "Utopia name" }
                if (metadata?.issuerUrl != null) {
                    th { +"Credential offer" }
                }
            }
            for (person in persons) {
                tr {
                    className = ClassName("records-table-row")
                    key = person.token
                    td { +person.familyName }
                    td { +person.givenName }
                    td {
                        a {
                            href = "person.html?token=${person.token}"
                            +person.utopiaId
                        }
                    }
                    if (metadata?.issuerUrl != null) {
                        td {
                            a {
                                href = "$serviceUrl/offer.html?token=${person.token}"
                                +"Credential offer"
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun fetchIdentityList(baseUrl: String): List<PersonBasicData> {
    val list = jsonPost("$baseUrl/identity/list", "identity list").jsonArray
    return list.map { token ->
        val person = jsonPost("$baseUrl/identity/get", "identity") {
            put("token", token)
            putJsonArray("core") {
                add("family_name")
                add("given_name")
                add("utopia_id_number")
            }
            putJsonObject("records") {}
        }
        val core = person.jsonObject["core"]!!.jsonObject
        PersonBasicData(
            token = token.jsonPrimitive.content,
            familyName = core["family_name"]?.jsonPrimitive?.content ?: "",
            givenName = core["given_name"]?.jsonPrimitive?.content ?: "",
            utopiaId = core["utopia_id_number"]!!.jsonPrimitive.content,
        )
    }
}

private data class PersonBasicData(
    val token: String,
    val familyName: String,
    val givenName: String,
    val utopiaId: String
)
