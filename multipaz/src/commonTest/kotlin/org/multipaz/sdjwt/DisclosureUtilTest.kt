package org.multipaz.sdjwt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.sdjwt.DisclosureUtil.putClaimDisclosureDigests
import org.multipaz.sdjwt.DisclosureUtil.toArrayDigestElement
import org.multipaz.sdjwt.DisclosureUtil.toArrayDisclosure
import org.multipaz.sdjwt.DisclosureUtil.toClaimDisclosure
import org.multipaz.sdjwt.DisclosureUtil.toDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class DisclosureUtilTest {

    @Test
    fun toDigest_propertyDisclosure_returnsCorrectDigest() = runTest {
        val digestOfDisclosure = propertyDisclosure.toDigest(digestAlgorithm)

        assertEquals("ttKtOqIx-sdPW5q0ST0dyqiVrllhMFo_xSHQBOBBj9I", digestOfDisclosure)
    }

    @Test
    fun toDigest_arrayDisclosure_returnsCorrectDigest() = runTest {
        val digestOfDisclosure = arrayDisclosure.toDigest(digestAlgorithm)

        assertEquals("sQYsJA0CEGpkqHjV1Orcn20MRXNORllVsemf_fNMM6o", digestOfDisclosure)
    }

    @Test
    fun toArrayDigestElement_validDisclosure_returnsCorrectJsonObject() = runTest {
        val result = arrayDisclosure.toArrayDigestElement(digestAlgorithm)

        assertEquals(
            """{"...":"${arrayDisclosure.toDigest(digestAlgorithm)}"}""",
            result.toString()
        )
    }

    @Test
    fun putClaimDisclosures_listOfDisclosureDigests_addsSdArray() = runTest {
        val resultJson = buildJsonObject {
            putClaimDisclosureDigests(
                listOf(propertyDisclosure, propertyDisclosure2),
                digestAlgorithm
            )
        }

        val expectedJsonString = """{"_sd":["${
            propertyDisclosure.toDigest(digestAlgorithm)
        }","${
            propertyDisclosure2.toDigest(digestAlgorithm)
        }"]}"""

        assertEquals(expectedJsonString, resultJson.toString())
    }

    @Test
    fun putClaimDisclosures_emptyListOfDisclosures_omitsSdArray() = runTest {
        val resultJson = buildJsonObject {
            putClaimDisclosureDigests(
                emptyList(),
                digestAlgorithm
            )
        }

        assertEquals("{}", resultJson.toString())
    }

    @Test
    fun toArrayDisclosure_createsCorrectDisclosure() {
        val element = JsonPrimitive("value")
        val result = element.toArrayDisclosure("salt")

        assertEquals("""["salt","value"]""", result.toString())
    }

    @Test
    fun toClaimDisclosure_createsCorrectDisclosure() {
        val element = JsonPrimitive("claimValue")
        val result = element.toClaimDisclosure("claimName", "salt")

        assertEquals("""["salt","claimName","claimValue"]""", result.toString())
    }


    companion object {
        private val digestAlgorithm = Algorithm.SHA256

        private val arrayDisclosure = JsonPrimitive("arrayValue").toArrayDisclosure("arraySalt")


        private val propertyDisclosure = JsonPrimitive("claimValue").toClaimDisclosure(
            "claimName",
            "claimSalt"
        )
        private val propertyDisclosure2 = JsonPrimitive("claimValue2").toClaimDisclosure(
            "claimName2",
            "claimSalt2"
        )
    }
}
