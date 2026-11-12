package org.multipaz.revocation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingRevocationCheckerTest {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun statusListOutOfBoundsJwt() = runTest {
        val dsKey = createTestDsKey()
        val iacaCert = dsKey.certChain.certificates.last()
        val builder = StatusList.Builder(1)
        builder.addStatus(3, 1)
        val uri = "https://issuer.example/statuslists/1"
        val jwt = builder.build().compress().serializeAsJwt(dsKey, uri)
        val engine = MockEngine {
            respond(
                content = jwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/statuslist+jwt")
            )
        }
        val checker = CachingRevocationChecker(EphemeralStorage(), HttpClient(engine))

        val validResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = 0, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = true
        )
        assertEquals(RevocationCheckState.VALID, validResult.state)
        assertTrue(validResult.isTrusted)
        assertNull(validResult.error)

        val revokedResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = 3, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = true
        )
        assertEquals(RevocationCheckState.INVALID, revokedResult.state)
        assertTrue(revokedResult.isTrusted)
        assertNull(revokedResult.error)

        val outOfBoundsResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = 100000, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = true
        )
        assertEquals(RevocationCheckState.UNKNOWN, outOfBoundsResult.state)
        assertFalse(outOfBoundsResult.isTrusted)
        assertTrue(outOfBoundsResult.error is IndexOutOfBoundsException)

        val negativeIndexResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = -1, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = true
        )
        assertEquals(RevocationCheckState.UNKNOWN, negativeIndexResult.state)
        assertFalse(negativeIndexResult.isTrusted)
        assertTrue(negativeIndexResult.error is IndexOutOfBoundsException)
    }

    @Test
    fun statusListOutOfBoundsCwt() = runTest {
        val dsKey = createTestDsKey()
        val iacaCert = dsKey.certChain.certificates.last()
        val builder = StatusList.Builder(2)
        builder.addStatus(1, 2)
        val uri = "https://issuer.example/statuslists/1"
        val cwt = builder.build().compress().serializeAsCwt(dsKey, uri)
        val engine = MockEngine {
            respond(
                content = cwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/statuslist+cwt")
            )
        }
        val checker = CachingRevocationChecker(EphemeralStorage(), HttpClient(engine))

        val suspendedResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = 1, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = false
        )
        assertEquals(RevocationCheckState.SUSPENDED, suspendedResult.state)
        assertTrue(suspendedResult.isTrusted)
        assertNull(suspendedResult.error)

        val outOfBoundsResult = checker.check(
            revocationStatus = RevocationStatus.StatusList(idx = 4, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = false
        )
        assertEquals(RevocationCheckState.UNKNOWN, outOfBoundsResult.state)
        assertFalse(outOfBoundsResult.isTrusted)
        assertTrue(outOfBoundsResult.error is IndexOutOfBoundsException)
    }

    @Test
    fun statusListSubjectMismatch() = runTest {
        val dsKey = createTestDsKey()
        val iacaCert = dsKey.certChain.certificates.last()
        val builder = StatusList.Builder(1)
        builder.addStatus(3, 1)
        val compressed = builder.build().compress()
        val wrongSub = "https://issuer.example/statuslists/other"
        val jwt = compressed.serializeAsJwt(dsKey, wrongSub)
        val cwt = compressed.serializeAsCwt(dsKey, wrongSub)

        val jwtEngine = MockEngine {
            respond(
                content = jwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/statuslist+jwt")
            )
        }
        val jwtChecker = CachingRevocationChecker(EphemeralStorage(), HttpClient(jwtEngine))
        val jwtResult = jwtChecker.check(
            revocationStatus = RevocationStatus.StatusList(
                idx = 0,
                uri = "https://issuer.example/statuslists/1",
                certificate = null
            ),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = true
        )
        assertEquals(RevocationCheckState.UNKNOWN, jwtResult.state)
        assertFalse(jwtResult.isTrusted)
        assertNotNull(jwtResult.error)

        val cwtEngine = MockEngine {
            respond(
                content = cwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/statuslist+cwt")
            )
        }
        val cwtChecker = CachingRevocationChecker(EphemeralStorage(), HttpClient(cwtEngine))
        val cwtResult = cwtChecker.check(
            revocationStatus = RevocationStatus.StatusList(
                idx = 0,
                uri = "https://issuer.example/statuslists/1",
                certificate = null
            ),
            issuerCert = iacaCert,
            onlyTrusted = true,
            preferJwt = false
        )
        assertEquals(RevocationCheckState.UNKNOWN, cwtResult.state)
        assertFalse(cwtResult.isTrusted)
        assertNotNull(cwtResult.error)
    }

    @Test
    fun identifierListSubjectMismatch() = runTest {
        val dsKey = createTestDsKey()
        val iacaCert = dsKey.certChain.certificates.last()
        val revokedId = ByteString("11223344".fromHex())
        val validId = ByteString("55667788".fromHex())
        val identifierList = IdentifierList(setOf(revokedId))
        val uri = "https://issuer.example/identifierlists/1"
        val validCwt = identifierList.serializeAsCwt(dsKey, uri)
        val wrongSubCwt = identifierList.serializeAsCwt(dsKey, "https://issuer.example/identifierlists/other")

        val validEngine = MockEngine {
            respond(
                content = validCwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/identifierlist+cwt")
            )
        }
        val validChecker = CachingRevocationChecker(EphemeralStorage(), HttpClient(validEngine))
        val revokedCheck = validChecker.check(
            revocationStatus = RevocationStatus.IdentifierList(id = revokedId, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true
        )
        assertEquals(RevocationCheckState.INVALID, revokedCheck.state)
        assertTrue(revokedCheck.isTrusted)

        val validCheck = validChecker.check(
            revocationStatus = RevocationStatus.IdentifierList(id = validId, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true
        )
        assertEquals(RevocationCheckState.VALID, validCheck.state)
        assertTrue(validCheck.isTrusted)

        val wrongSubEngine = MockEngine {
            respond(
                content = wrongSubCwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/identifierlist+cwt")
            )
        }
        val wrongSubChecker = CachingRevocationChecker(EphemeralStorage(), HttpClient(wrongSubEngine))
        val wrongSubCheck = wrongSubChecker.check(
            revocationStatus = RevocationStatus.IdentifierList(id = validId, uri = uri, certificate = null),
            issuerCert = iacaCert,
            onlyTrusted = true
        )
        assertEquals(RevocationCheckState.UNKNOWN, wrongSubCheck.state)
        assertFalse(wrongSubCheck.isTrusted)
        assertNotNull(wrongSubCheck.error)
    }
}
