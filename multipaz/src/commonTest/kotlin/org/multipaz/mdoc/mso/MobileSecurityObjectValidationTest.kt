package org.multipaz.mdoc.mso

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.TestVectors
import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MobileSecurityObjectValidationTest {

    private val deviceKey = EcPublicKeyDoubleCoordinate(
        curve = EcCurve.P256,
        x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
    )

    private val validDate = LocalDate.parse("2026-01-01").atStartOfDayIn(TimeZone.UTC)

    private fun createValidMso(): MobileSecurityObject {
        return MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = validDate,
            validFrom = validDate,
            validUntil = validDate + 30.days,
            expectedUpdate = validDate + 15.days,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = mapOf(
                "org.iso.18013.5.1" to mapOf(
                    0L to ByteString(ByteArray(32) { 1.toByte() }),
                    1L to ByteString(ByteArray(32) { 2.toByte() })
                )
            ),
            deviceKey = deviceKey
        )
    }

    @Test
    fun testValidMso() {
        val mso = createValidMso()
        val result = mso.validate(now = validDate + 5.days)
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun testAnnexDVectorMso() {
        val deviceResponse = Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex())
        val documentDataItem = deviceResponse["documents"][0]
        val issuerSigned = documentDataItem["issuerSigned"]
        val issuerAuthDataItem = issuerSigned["issuerAuth"]
        val mobileSecurityObjectBytes = Cbor.decode(issuerAuthDataItem.asCoseSign1.payload!!)
        val mso = MobileSecurityObject.fromDataItem(mobileSecurityObjectBytes.asTaggedEncodedCbor)

        // Validate at the time it was valid (e.g. 2020-11-01)
        val testTime = Instant.parse("2020-11-01T00:00:00Z")
        val result = mso.validate(now = testTime)
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun testVersionValidation() {
        val mso11 = createValidMso().copy(version = "1.1")
        assertFalse(mso11.validate(now = validDate + 5.days).hasErrors)
        assertFalse(mso11.validate(now = validDate + 5.days).hasWarnings)

        val msoEmpty = createValidMso().copy(version = "")
        assertTrue(msoEmpty.validate(now = validDate + 5.days).hasErrors)
        assertTrue(msoEmpty.validate(now = validDate + 5.days).errors.any { it.message.contains("version cannot be empty") })

        val msoUnknown = createValidMso().copy(version = "2.0")
        assertFalse(msoUnknown.validate(now = validDate + 5.days).hasErrors)
        assertTrue(msoUnknown.validate(now = validDate + 5.days).hasWarnings)
        assertTrue(msoUnknown.validate(now = validDate + 5.days).warnings.any { it.message.contains("unrecognized") })
    }

    @Test
    fun testDigestAlgorithmValidation() {
        val msoSha384 = createValidMso().copy(
            digestAlgorithm = Algorithm.SHA384,
            valueDigests = mapOf("ns" to mapOf(0L to ByteString(ByteArray(48))))
        )
        assertFalse(msoSha384.validate(now = validDate + 5.days).hasErrors)

        val msoSha512 = createValidMso().copy(
            digestAlgorithm = Algorithm.SHA512,
            valueDigests = mapOf("ns" to mapOf(0L to ByteString(ByteArray(64))))
        )
        assertFalse(msoSha512.validate(now = validDate + 5.days).hasErrors)

        val msoUnsupported = createValidMso().copy(digestAlgorithm = Algorithm.ES256)
        assertTrue(msoUnsupported.validate(now = validDate + 5.days).hasErrors)
        assertTrue(msoUnsupported.validate(now = validDate + 5.days).errors.any { it.message.contains("Unsupported digest algorithm") })
    }

    @Test
    fun testDigestLengthMismatch() {
        // SHA-256 expects 32 bytes, provided 20 bytes
        val msoWrongLength = createValidMso().copy(
            valueDigests = mapOf("ns" to mapOf(0L to ByteString(ByteArray(20))))
        )
        val result = msoWrongLength.validate(now = validDate + 5.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("expected 32 bytes") })
    }

    @Test
    fun testSameDigestIdAcrossNamespacesAllowed() {
        val mso = createValidMso().copy(
            valueDigests = mapOf(
                "ns1" to mapOf(0L to ByteString(ByteArray(32))),
                "ns2" to mapOf(0L to ByteString(ByteArray(32))) // digestID 0 in both ns1 and ns2 is allowed
            )
        )
        val result = mso.validate(now = validDate + 5.days)
        assertFalse(result.hasErrors)
    }

    @Test
    fun testFractionalSeconds() {
        val msoWithFraction = createValidMso().copy(
            signedAt = validDate + 100.nanoseconds
        )
        val result = msoWithFraction.validate(now = validDate + 5.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("fractional seconds") })
    }

    @Test
    fun testTimestampOrdering() {
        // validFrom before signed
        val msoEarlyValidFrom = createValidMso().copy(
            signedAt = validDate + 10.seconds,
            validFrom = validDate
        )
        assertTrue(msoEarlyValidFrom.validate(now = validDate + 5.days).hasErrors)
        assertTrue(msoEarlyValidFrom.validate(now = validDate + 5.days).errors.any { it.message.contains("equal to or later than signed") })

        // validUntil before validFrom
        val msoExpired = createValidMso().copy(
            validFrom = validDate + 10.days,
            validUntil = validDate
        )
        assertTrue(msoExpired.validate(now = validDate + 5.days).hasErrors)
        assertTrue(msoExpired.validate(now = validDate + 5.days).errors.any { it.message.contains("must be later than validFrom") })

        // expectedUpdate after validUntil
        val msoLateUpdate = createValidMso().copy(
            validUntil = validDate + 10.days,
            expectedUpdate = validDate + 20.days
        )
        assertTrue(msoLateUpdate.validate(now = validDate + 5.days).hasWarnings)
        assertTrue(msoLateUpdate.validate(now = validDate + 5.days).warnings.any { it.message.contains("expectedUpdate") })
    }

    @Test
    fun testNowValidityChecks() {
        val mso = createValidMso()

        // Before validFrom -> Warning
        val notYetValidResult = mso.validate(now = validDate - 1.days)
        assertFalse(notYetValidResult.hasErrors)
        assertTrue(notYetValidResult.hasWarnings)
        assertTrue(notYetValidResult.warnings.any { it.message.contains("not yet valid") })

        // After validUntil -> Error
        val expiredResult = mso.validate(now = validDate + 35.days)
        assertTrue(expiredResult.hasErrors)
        assertTrue(expiredResult.errors.any { it.message.contains("expired") })
    }

    @Test
    fun testValidateDataItem() {
        val validMso = createValidMso()
        val validResult = MobileSecurityObject.validate(validMso.toDataItem(), now = validDate + 5.days)
        assertFalse(validResult.hasErrors)

        // Missing required keys
        val missingKeysCbor = buildCborMap {
            put("version", "1.0")
        }
        val invalidResult = MobileSecurityObject.validate(missingKeysCbor, now = validDate + 5.days)
        assertTrue(invalidResult.hasErrors)
        assertTrue(invalidResult.errors.any { it.message.contains("missing required key") })
    }

    @Test
    fun testKeylessMsoValidation() {
        val keylessMovieTicketMso = MobileSecurityObject(
            version = "1.0",
            docType = "org.multipaz.movieticket.1",
            signedAt = validDate,
            validFrom = validDate,
            validUntil = validDate + 30.days,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = mapOf(
                "org.multipaz.movieticket.1" to mapOf(
                    0L to ByteString(ByteArray(32) { 1.toByte() })
                )
            ),
            deviceKey = null
        )
        val instanceResult = keylessMovieTicketMso.validate(now = validDate + 5.days)
        assertFalse(instanceResult.hasErrors)
        assertFalse(instanceResult.hasWarnings)

        val cborResult = MobileSecurityObject.validate(
            keylessMovieTicketMso.toDataItem(),
            now = validDate + 5.days
        )
        assertFalse(cborResult.hasErrors)
        assertFalse(cborResult.hasWarnings)

        // ISO/IEC 18013-5 Clause 13.3: mDL must always include deviceKeyInfo
        val mdlMissingDeviceKeyCbor = buildCborMap {
            put("version", "1.0")
            put("digestAlgorithm", "SHA-256")
            put("docType", "org.iso.18013.5.1.mDL")
            putCborMap("valueDigests") {
                putCborMap("org.iso.18013.5.1") {
                    put(0L, ByteArray(32) { 1.toByte() })
                }
            }
            putCborMap("validityInfo") {
                put("signed", validDate.toDataItemDateTimeString())
                put("validFrom", validDate.toDataItemDateTimeString())
                put("validUntil", (validDate + 30.days).toDataItemDateTimeString())
            }
        }
        val mdlKeylessResult = MobileSecurityObject.validate(
            mdlMissingDeviceKeyCbor,
            now = validDate + 5.days
        )
        assertTrue(mdlKeylessResult.hasErrors)
        assertTrue(mdlKeylessResult.errors.any { it.message.contains("deviceKeyInfo") })
    }
}
