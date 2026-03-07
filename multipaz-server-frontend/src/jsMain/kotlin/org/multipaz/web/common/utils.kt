package org.multipaz.web.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.MainScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import web.window.window

val httpClient = HttpClient(Js)

val mainScope = MainScope()

fun resolveUrl(path: String): String =
    URLBuilder(window.location.href).apply {
        parameters.clear()
        takeFrom(path)
    }.build().toString()

suspend fun jsonGet(url: String, name: String? = null): JsonElement =
    jsonFromResponse(httpClient.get(url), name ?: url)

suspend fun jsonPost(url: String, name: String? = null): JsonElement =
    jsonFromResponse(httpClient.post(url), name ?: url)

suspend fun jsonPost(
    url: String,
    name: String? = null,
    block: suspend JsonObjectBuilder.() -> Unit
): JsonElement =
    jsonFromResponse(httpClient.post(url) {
        headers {
            contentType(ContentType.Application.Json)
        }
        setBody(buildJsonObject { block.invoke(this) }.toString())
    }, name ?: url)

suspend fun jsonFromResponse(
    httpResponse: HttpResponse,
    name: String,
): JsonElement {
    if (httpResponse.status != HttpStatusCode.OK) {
        throw IllegalStateException("Error loading $name")
    }
    return Json.parseToJsonElement(httpResponse.readRawBytes().decodeToString())
}