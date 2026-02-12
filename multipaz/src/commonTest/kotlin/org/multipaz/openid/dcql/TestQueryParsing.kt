package org.multipaz.openid.dcql

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.PrettyPrinter
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import kotlin.test.Test
import kotlin.test.assertEquals

internal fun DcqlQuery.print(): String {
    val pp = PrettyPrinter()
    this.print(pp)
    return pp.toString()
}


internal fun DcqlQuery.print(pp: PrettyPrinter) {
    pp.append("credentials:")
    pp.pushIndent()
    credentialQueries.forEach {
        pp.append("credential:")
        pp.pushIndent()
        it.print(pp)
        pp.popIndent()
    }
    pp.popIndent()

    pp.append("credentialSets:")
    pp.pushIndent()
    if (credentialSetQueries.isNotEmpty()) {
        credentialSetQueries.forEach {
            pp.append("credentialSet:")
            pp.pushIndent()
            it.print(pp)
            pp.popIndent()
        }
    } else {
        pp.append("<empty>")
    }
    pp.popIndent()
}

internal fun DcqlCredentialSetQuery.print(pp: PrettyPrinter) {
    pp.append("required: $required")
    pp.append("options:")
    pp.pushIndent()
    for (option in options) {
        option.print(pp)
    }
    pp.popIndent()
}

internal fun DcqlCredentialSetOption.print(pp: PrettyPrinter) {
    pp.append("$credentialIds")
}

internal fun DcqlCredentialQuery.print(pp: PrettyPrinter) {
    pp.append("id: $id")
    pp.append("format: $format")
    if (mdocDocType != null) {
        pp.append("mdocDocType: $mdocDocType")
    }
    if (vctValues != null) {
        pp.append("vctValues: $vctValues")
    }
    pp.append("claims:")
    pp.pushIndent()
    claims.forEach {
        pp.append("claim:")
        pp.pushIndent()
        it.print(pp)
        pp.popIndent()
    }
    pp.popIndent()
    pp.append("claimSets:")
    pp.pushIndent()
    if (claimSets.isNotEmpty()) {
        claimSets.forEach {
            pp.append("claimset:")
            pp.pushIndent()
            it.print(pp)
            pp.popIndent()
        }
    } else {
        pp.append("<empty>")
    }
    pp.popIndent()
}

internal fun DcqlClaimSet.print(pp: PrettyPrinter) {
    pp.append("ids: $claimIdentifiers")
}

internal fun RequestedClaim.print(pp: PrettyPrinter) {
    if (id != null) {
        pp.append("id: $id")
    }
    when (this) {
        is JsonRequestedClaim -> {
            pp.append("path: $claimPath")
        }
        is MdocRequestedClaim -> {
            pp.append("path: [\"$namespaceName\",\"$dataElementName\"]")
            if (intentToRetain) {
                pp.append("mdocIntentToRetain: $intentToRetain")
            }
        }
    }
    if (values != null) {
        pp.append("values: $values")
    }
}

class TestQueryParsing {

    @Test
    fun simple() {
        assertEquals(
            """
                credentials:
                  credential:
                    id: my_credential
                    format: mso_mdoc
                    mdocDocType: org.iso.7367.1.mVRC
                    claims:
                      claim:
                        path: ["org.iso.7367.1","vehicle_holder"]
                      claim:
                        path: ["org.iso.18013.5.1","first_name"]
                    claimSets:
                      <empty>
                credentialSets:
                  <empty>
              """.trimIndent().trim(),
            DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                    {
                      "credentials": [
                        {
                          "id": "my_credential",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.7367.1.mVRC"
                          },
                          "claims": [
                            {"path": ["org.iso.7367.1", "vehicle_holder"]},
                            {"path": ["org.iso.18013.5.1", "first_name"]}
                          ]
                        }
                      ]
                    }
                """
                ).jsonObject
            ).print().trim()
        )
    }

    @Test
    fun claimSet() {
        assertEquals(
            """
                credentials:
                  credential:
                    id: pid
                    format: dc+sd-jwt
                    vctValues: [https://credentials.example.com/identity_credential]
                    claims:
                      claim:
                        id: a
                        path: ["last_name"]
                      claim:
                        id: b
                        path: ["postal_code"]
                      claim:
                        id: c
                        path: ["locality"]
                      claim:
                        id: d
                        path: ["region"]
                      claim:
                        id: e
                        path: ["date_of_birth"]
                    claimSets:
                      claimset:
                        ids: [a, c, d, e]
                      claimset:
                        ids: [a, b, e]
                credentialSets:
                  <empty>
            """.trimIndent().trim(),
            DcqlQuery.fromJson(Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [ "https://credentials.example.com/identity_credential" ]
                          },
                          "claims": [
                            {"id": "a", "path": ["last_name"]},
                            {"id": "b", "path": ["postal_code"]},
                            {"id": "c", "path": ["locality"]},
                            {"id": "d", "path": ["region"]},
                            {"id": "e", "path": ["date_of_birth"]}
                          ],
                          "claim_sets": [
                            ["a", "c", "d", "e"],
                            ["a", "b", "e"]
                          ]
                        }
                      ]
                    }
        """
            ).jsonObject).print().trim()
        )
    }

    @Test
    fun credentialSet() {
        assertEquals(
            """
                credentials:
                  credential:
                    id: pid
                    format: dc+sd-jwt
                    vctValues: [https://credentials.example.com/identity_credential]
                    claims:
                      claim:
                        path: ["given_name"]
                      claim:
                        path: ["family_name"]
                      claim:
                        path: ["address","street_address"]
                    claimSets:
                      <empty>
                  credential:
                    id: other_pid
                    format: dc+sd-jwt
                    vctValues: [https://othercredentials.example/pid]
                    claims:
                      claim:
                        path: ["given_name"]
                      claim:
                        path: ["family_name"]
                      claim:
                        path: ["address","street_address"]
                    claimSets:
                      <empty>
                  credential:
                    id: pid_reduced_cred_1
                    format: dc+sd-jwt
                    vctValues: [https://credentials.example.com/reduced_identity_credential]
                    claims:
                      claim:
                        path: ["family_name"]
                      claim:
                        path: ["given_name"]
                    claimSets:
                      <empty>
                  credential:
                    id: pid_reduced_cred_2
                    format: dc+sd-jwt
                    vctValues: [https://cred.example/residence_credential]
                    claims:
                      claim:
                        path: ["postal_code"]
                      claim:
                        path: ["locality"]
                      claim:
                        path: ["region"]
                    claimSets:
                      <empty>
                  credential:
                    id: nice_to_have
                    format: dc+sd-jwt
                    vctValues: [https://company.example/company_rewards]
                    claims:
                      claim:
                        path: ["rewards_number"]
                    claimSets:
                      <empty>
                credentialSets:
                  credentialSet:
                    required: true
                    options:
                      [pid]
                      [other_pid]
                      [pid_reduced_cred_1, pid_reduced_cred_2]
                  credentialSet:
                    required: false
                    options:
                      [nice_to_have]
            """.trimIndent().trim(),
            DcqlQuery.fromJson(Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://credentials.example.com/identity_credential"]
                          },
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]},
                            {"path": ["address", "street_address"]}
                          ]
                        },
                        {
                          "id": "other_pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://othercredentials.example/pid"]
                          },
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]},
                            {"path": ["address", "street_address"]}
                          ]
                        },
                        {
                          "id": "pid_reduced_cred_1",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
                          },
                          "claims": [
                            {"path": ["family_name"]},
                            {"path": ["given_name"]}
                          ]
                        },
                        {
                          "id": "pid_reduced_cred_2",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://cred.example/residence_credential"]
                          },
                          "claims": [
                            {"path": ["postal_code"]},
                            {"path": ["locality"]},
                            {"path": ["region"]}
                          ]
                        },
                        {
                          "id": "nice_to_have",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://company.example/company_rewards"]
                          },
                          "claims": [
                            {"path": ["rewards_number"]}
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "pid" ],
                            [ "other_pid" ],
                            [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
                          ]
                        },
                        {
                          "required": false,
                          "options": [
                            [ "nice_to_have" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject).print().trim()
        )

    }
}