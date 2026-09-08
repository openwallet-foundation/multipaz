package org.multipaz.verifier.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests

suspend fun cannedRequests(call: ApplicationCall) {
    val zkSystemRepository = BackendEnvironment.getInterface(ZkSystemRepository::class)
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonArray {
            for (dt in documentTypeRepo.documentTypes) {
                val jsonRequests = dt.cannedRequests.filter { it.jsonRequest != null }
                if (jsonRequests.isNotEmpty()) {
                    addJsonObject {
                        put("display_name", dt.displayName + " (VC)")
                        putJsonArray("requests") {
                            for (singleDocumentRequest in jsonRequests) {
                                addSingleDocumentRequest(
                                    singleDocumentRequest = singleDocumentRequest,
                                    zkSystemRepository = zkSystemRepository,
                                    mdoc = false
                                )
                            }
                        }
                    }
                }
                val mdocRequests = dt.cannedRequests.filter { it.mdocRequest != null }
                if (mdocRequests.isNotEmpty()) {
                    addJsonObject {
                        put("display_name", dt.displayName + " (mdoc)")
                        putJsonArray("requests") {
                            for (singleDocumentRequest in mdocRequests) {
                                addSingleDocumentRequest(
                                    singleDocumentRequest = singleDocumentRequest,
                                    zkSystemRepository = zkSystemRepository,
                                    mdoc = true
                                )
                            }
                        }
                    }
                }
            }
            val extraJsonRequests = documentTypeRepo.extraSingleDocumentCannedRequests.filter {
                it.jsonRequest != null
            }
            if (extraJsonRequests.isNotEmpty()) {
                addJsonObject {
                    put("display_name", "Extra requests (json)")
                    putJsonArray("requests") {
                        for (request in extraJsonRequests) {
                            if (request.jsonRequest != null) {
                                addSingleDocumentRequest(
                                    singleDocumentRequest = request,
                                    zkSystemRepository = zkSystemRepository,
                                    mdoc = false
                                )
                            }
                        }
                    }
                }
            }
            val extraMdocRequests = documentTypeRepo.extraSingleDocumentCannedRequests.filter {
                it.mdocRequest != null
            }
            if (extraMdocRequests.isNotEmpty()) {
                addJsonObject {
                    put("display_name", "Extra requests (mdoc)")
                    putJsonArray("requests") {
                        for (request in extraMdocRequests) {
                            if (request.jsonRequest != null) {
                                addSingleDocumentRequest(
                                    singleDocumentRequest = request,
                                    zkSystemRepository = zkSystemRepository,
                                    mdoc = false
                                )
                            }
                            if (request.mdocRequest != null) {
                                addSingleDocumentRequest(
                                    singleDocumentRequest = request,
                                    zkSystemRepository = zkSystemRepository,
                                    mdoc = true
                                )
                            }
                        }
                    }
                }
            }
            addJsonObject {
                put("display_name", "Multi-document requests")
                putJsonArray("requests") {
                    for (mdr in wellKnownMultipleDocumentRequests) {
                        addJsonObject {
                            put("display_name", mdr.displayName)
                            put("dcql", Json.parseToJsonElement(mdr.dcqlString))
                            mdr.transactionData?.let {
                                put("transaction_data", Json.parseToJsonElement(it))
                            }
                        }
                    }
                }
            }
        }.toString()
    )
}

fun JsonArrayBuilder.addSingleDocumentRequest(
    singleDocumentRequest: SingleDocumentCannedRequest,
    zkSystemRepository: ZkSystemRepository?,
    mdoc: Boolean
) {
    addJsonObject {
        put("display_name", singleDocumentRequest.displayName)
        if (mdoc) {
            put("dcql", singleDocumentRequest.mdocRequest!!.toDcql(
                zkSystemSpecs = zkSystemRepository?.getAllZkSystemSpecs() ?: emptyList()
            ))
        } else {
            put("dcql", singleDocumentRequest.jsonRequest!!.toDcql())
        }
        if (singleDocumentRequest.transactionData.isNotEmpty()) {
            putJsonArray("transaction_data") {
                for (transactionData in singleDocumentRequest.transactionData) {
                    val serialized = transactionData.getSerializedJson(listOf("cred1"))
                    add(Json.parseToJsonElement(serialized))
                }
            }
        }
    }
}