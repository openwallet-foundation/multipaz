package org.multipaz.revocation

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentifierListTest {
    @Test
    fun roundtripCwt() = runTest {
        val dsKey = createTestDsKey()
        val iacaCert = dsKey.certChain.certificates.last()
        val dsCert = dsKey.certChain.certificates.first()
        val id1 = ByteString("1122334455667788990011223344556677889900112233445566778899001122".fromHex())
        val id2 = ByteString("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899".fromHex())
        val absentId = ByteString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff".fromHex())

        val creationTime = dsCert.validityNotBefore
        val expirationTime = dsCert.validityNotAfter

        val identifierList = IdentifierList(
            identifiers = setOf(id1, id2),
            creationTime = creationTime,
            expirationTime = expirationTime
        )

        val cwt = identifierList.serializeAsCwt(dsKey, "https://example.com/identifierlists/1")

        val decodedCwt = Cdn.encode(cwt, CdnGeneratorOptions.Pretty)
        val lines = decodedCwt.lines().toMutableList()
        lines[lines.size - 2] = "  /signature/ h'' # Signature redacted"
        val sanitizedCdn = lines.joinToString("\n")

        val expectedCdn = """
            18([ # COSE_Sign1
              /protected/ << {
                16: "application/identifierlist+cwt",
                /x5chain/ 33: [
                  # Subject DN: C=US,CN=DS test key
                  # Issuer DN: C=US,CN=IACA test key
                  cert'''${"\n" + dsCert.toPem().trimEnd().prependIndent("                    ")}
                  ''',
                  # Subject DN: C=US,CN=IACA test key
                  # Issuer DN: C=US,CN=IACA test key
                  cert'''${"\n" + iacaCert.toPem().trimEnd().prependIndent("                    ")}
                  '''
                ],
                /alg/ 1: -9 # ESP256: ECDSA using P-256 curve and SHA-256
              } >>,
              /unprotected/ {},
              /payload/ << {
                4: ${expirationTime.epochSeconds},
                6: ${creationTime.epochSeconds},
                2: "https://example.com/identifierlists/1",
                65530: {
                  "identifiers": {
                    h'1122334455667788990011223344556677889900112233445566778899001122': {},
                    h'aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899': {}
                  }
                },
                65534: 86400
              } >>,
              /signature/ h'' # Signature redacted
            ])
        """.trimIndent()

        assertEquals(expectedCdn, sanitizedCdn)

        val parsedList = IdentifierList.fromCwt(cwt, iacaCert, atTime = creationTime + 100.seconds)

        assertTrue(parsedList.contains(id1))
        assertTrue(parsedList.contains(id2))
        assertFalse(parsedList.contains(absentId))
    }
}
