package org.multipaz.mdoc.issuersigned

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.util.fromHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class IssuerNamespacesValidationTest {

    @Test
    fun testValidIssuerSignedItem() {
        val item = IssuerSignedItem.fromValues(
            digestId = 0,
            random = ByteString(ByteArray(16) { 0x42.toByte() }),
            dataElementIdentifier = "family_name",
            dataElementValue = "Doe".toDataItem()
        )
        val result = item.validate()
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun testIssuerSignedItemRandomTooShort() {
        val item = IssuerSignedItem.fromValues(
            digestId = 0,
            random = ByteString(ByteArray(15) { 0x42.toByte() }), // Must be at least 16 bytes
            dataElementIdentifier = "family_name",
            dataElementValue = "Doe".toDataItem()
        )
        val result = item.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("at least 16 bytes") })
    }

    @Test
    fun testIssuerSignedItemNegativeDigestId() {
        val item = IssuerSignedItem(
            buildCborMap {
                put("digestID", -1L)
                put("random", ByteArray(16))
                put("elementIdentifier", "family_name")
                put("elementValue", "Doe".toDataItem())
            }
        )
        val result = item.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("non-negative") })
    }

    @Test
    fun testIssuerSignedItemEmptyIdentifier() {
        val item = IssuerSignedItem.fromValues(
            digestId = 0,
            random = ByteString(ByteArray(16)),
            dataElementIdentifier = "",
            dataElementValue = "Doe".toDataItem()
        )
        val result = item.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("cannot be empty") })
    }

    @Test
    fun testIssuerSignedItemExtraKeys() {
        val item = IssuerSignedItem(
            buildCborMap {
                put("digestID", 0L)
                put("random", ByteArray(16))
                put("elementIdentifier", "family_name")
                put("elementValue", "Doe".toDataItem())
                put("extraKey", "unexpected")
            }
        )
        val result = item.validate()
        assertFalse(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.any { it.message.contains("unexpected keys") })
    }

    @Test
    fun testValidIssuerNamespaces() {
        val namespaces = buildIssuerNamespaces(
            dataElementRandomSize = 16,
            randomProvider = Random(42)
        ) {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("family_name", "Doe".toDataItem())
                addDataElement("given_name", "John".toDataItem())
            }
            addNamespace("org.iso.18013.5.1.aamva") {
                addDataElement("organ_donor", 1L.toDataItem())
            }
        }
        val result = namespaces.validate()
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun testIssuerNamespacesDuplicateDigestId() {
        val item1 = IssuerSignedItem.fromValues(
            digestId = 42L,
            random = ByteString(ByteArray(16)),
            dataElementIdentifier = "elem1",
            dataElementValue = "value1".toDataItem()
        )
        val item2 = IssuerSignedItem.fromValues(
            digestId = 42L, // duplicate!
            random = ByteString(ByteArray(16)),
            dataElementIdentifier = "elem2",
            dataElementValue = "value2".toDataItem()
        )
        val namespacesDuplicateInSameNs = IssuerNamespaces(
            mapOf(
                "ns1" to mapOf("elem1" to item1, "elem2" to item2)
            )
        )
        val result = namespacesDuplicateInSameNs.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("Duplicate digestID 42") })

        val namespacesDifferentNs = IssuerNamespaces(
            mapOf(
                "ns1" to mapOf("elem1" to item1),
                "ns2" to mapOf("elem2" to item2)
            )
        )
        val resultDifferent = namespacesDifferentNs.validate()
        assertFalse(resultDifferent.hasErrors)
    }

    @Test
    fun testIssuerNamespacesEmpty() {
        val namespaces = IssuerNamespaces(emptyMap())
        val result = namespaces.validate()
        assertFalse(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.any { it.message.contains("has no namespaces") })
    }

    @Test
    fun testIssuerNamespacesValidateDataItem() {
        // Valid DataItem
        val validNamespaces = buildIssuerNamespaces(
            dataElementRandomSize = 16,
            randomProvider = Random(42)
        ) {
            addNamespace("ns1") {
                addDataElement("elem1", "val1".toDataItem())
            }
        }
        val validResult = IssuerNamespaces.validate(validNamespaces.toDataItem())
        assertFalse(validResult.hasErrors)

        // Invalid: element not wrapped in Tag 24
        val invalidCbor = buildCborMap {
            put("ns1", buildCborArray {
                add(buildCborMap {
                    put("digestID", 0L)
                    put("random", ByteArray(16))
                    put("elementIdentifier", "elem1")
                    put("elementValue", "val1".toDataItem())
                })
            })
        }
        val invalidResult = IssuerNamespaces.validate(invalidCbor)
        assertTrue(invalidResult.hasErrors)
        assertTrue(invalidResult.errors.any { it.message.contains("not tagged with CBOR tag 24") })

        // Invalid: duplicate elementIdentifier in same namespace array
        val itemBytes = Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(buildCborMap {
                put("digestID", 0L)
                put("random", ByteArray(16))
                put("elementIdentifier", "duplicate_elem")
                put("elementValue", "val1".toDataItem())
            }))
        )
        val itemBytes2 = Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(buildCborMap {
                put("digestID", 1L)
                put("random", ByteArray(16))
                put("elementIdentifier", "duplicate_elem")
                put("elementValue", "val2".toDataItem())
            }))
        )
        val duplicateElemCbor = buildCborMap {
            put("ns1", buildCborArray {
                add(itemBytes)
                add(itemBytes2)
            })
        }
        val duplicateResult = IssuerNamespaces.validate(duplicateElemCbor)
        assertTrue(duplicateResult.hasErrors)
        assertTrue(duplicateResult.errors.any { it.message.contains("Duplicate elementIdentifier 'duplicate_elem'") })
    }

    @Test
    fun testValidateAgainstMso() = runTest {
        val issuerNamespaces = buildIssuerNamespaces(
            dataElementRandomSize = 16,
            randomProvider = Random(42)
        ) {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("family_name", "Doe".toDataItem())
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val d = LocalDate.parse("2026-01-01").atStartOfDayIn(TimeZone.UTC)
        val deviceKey = EcPublicKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
            y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
        )
        val validMso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = d,
            validFrom = d,
            validUntil = d + 30.days,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = deviceKey
        )

        // Matching MSO passes
        val validResult = issuerNamespaces.validateAgainstMso(validMso)
        assertFalse(validResult.hasErrors)
        assertFalse(validResult.hasWarnings)

        // MSO missing a namespace
        val msoMissingNamespace = validMso.copy(valueDigests = emptyMap())
        val missingNsResult = issuerNamespaces.validateAgainstMso(msoMissingNamespace)
        assertTrue(missingNsResult.hasErrors)
        assertTrue(missingNsResult.errors.any { it.message.contains("not present in MSO valueDigests") })

        // MSO with corrupted digest value
        val corruptedDigests = validMso.valueDigests.mapValues { (_, inner) ->
            inner.mapValues { ByteString(ByteArray(32) { 0xFF.toByte() }) }
        }
        val msoCorrupted = validMso.copy(valueDigests = corruptedDigests)
        val corruptedResult = issuerNamespaces.validateAgainstMso(msoCorrupted)
        assertTrue(corruptedResult.hasErrors)
        assertTrue(corruptedResult.errors.any { it.message.contains("Digest mismatch") })

        // MSO with extra namespace (Warning)
        val extraDigests = validMso.valueDigests.toMutableMap().apply {
            put("extra.namespace", mapOf(99L to ByteString(ByteArray(32))))
        }
        val msoExtra = validMso.copy(valueDigests = extraDigests)
        val extraResult = issuerNamespaces.validateAgainstMso(msoExtra)
        assertFalse(extraResult.hasErrors)
        assertTrue(extraResult.hasWarnings)
        assertTrue(extraResult.warnings.any { it.message.contains("missing from IssuerNamespaces") })
    }
}
