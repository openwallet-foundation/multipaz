package org.multipaz.presentment

import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UriSchemePresentmentDirectPostCallbackTest {

    @Test
    fun returnsRedirectUriForJsonCallbackBody() {
        val redirectUri = extractRedirectUriFromDirectPostCallback(
            responseContentType = ContentType.Application.Json,
            responseBodyBytes = "{\"redirect_uri\":\"https://wallet.example/cb\"}".encodeToByteArray()
        )

        assertEquals("https://wallet.example/cb", redirectUri)
    }

    @Test
    fun returnsNullWhenJsonBodyIsMalformed() {
        val redirectUri = extractRedirectUriFromDirectPostCallback(
            responseContentType = ContentType.Application.Json,
            responseBodyBytes = "{not-json".encodeToByteArray()
        )

        assertNull(redirectUri)
    }

    @Test
    fun returnsNullWhenContentTypeIsNotJson() {
        val redirectUri = extractRedirectUriFromDirectPostCallback(
            responseContentType = ContentType.Text.Plain,
            responseBodyBytes = "{\"redirect_uri\":\"https://wallet.example/cb\"}".encodeToByteArray()
        )

        assertNull(redirectUri)
    }
}
