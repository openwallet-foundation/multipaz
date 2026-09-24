package org.multipaz.presentment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the query parsing behind [uriSchemePresentment].
 *
 * Running a whole URI through `parseUrlEncodedParameters()` folds the scheme into the first key,
 * so `openid4vp://?request_uri=...` used to lose `request_uri` and only worked when some other
 * parameter happened to precede it.
 */
class UriSchemeQueryParsingTest {

    @Test
    fun requestUriAsOnlyParameter() {
        val parameters = parseQueryParameters(
            "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc"
        )
        assertEquals("https://verifier.example/oid4vp/request/abc", parameters["request_uri"])
    }

    @Test
    fun requestUriAfterClientId() {
        val parameters = parseQueryParameters(
            "openid4vp://?client_id=x509_san_dns%3Averifier.example" +
                    "&request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc"
        )
        assertEquals("x509_san_dns:verifier.example", parameters["client_id"])
        assertEquals("https://verifier.example/oid4vp/request/abc", parameters["request_uri"])
    }

    @Test
    fun schemeIsNotFoldedIntoTheFirstKey() {
        val parameters = parseQueryParameters("haip-vp://?request_uri=https%3A%2F%2Fexample")
        assertEquals(setOf("request_uri"), parameters.names())
    }

    @Test
    fun requestUriMethodIsRead() {
        val parameters = parseQueryParameters(
            "openid4vp://?request_uri=https%3A%2F%2Fexample&request_uri_method=post"
        )
        assertEquals("post", parameters["request_uri_method"])
    }

    @Test
    fun noQueryYieldsNoParameters() {
        assertNull(parseQueryParameters("openid4vp://")["request_uri"])
    }
}
