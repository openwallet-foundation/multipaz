package org.multipaz.records.payment

import kotlinx.io.bytestring.ByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.multipaz.cbor.buildCborArray
import org.multipaz.verification.Iso18013PresentmentRecord
import org.multipaz.verification.OpenID4VPPresentmentRecord
import org.junit.Test

/**
 * Unit tests for [requiresNonceVerification], the gate that decides whether a presentment's verifier
 * nonce is checked before a payment is committed. The security contract is fail-closed: only an ISO
 * 18013-5 *proximity* record (no `encryptionInfo`, no `origin`) may skip the check; everything else
 * must verify.
 */
class RequiresNonceVerificationTest {

    // requiresNonceVerification never inspects these, so an empty CBOR array is a fine placeholder.
    private fun isoRecord(encryptionInfo: ByteString?, origin: String?) =
        Iso18013PresentmentRecord(
            response = buildCborArray {},
            sessionTranscript = buildCborArray {},
            request = buildCborArray {},
            eDeviceKey = null,
            encryptionInfo = encryptionInfo,
            origin = origin,
        )

    @Test
    fun proximityIsoRecordSkipsNonceVerification() {
        // NFC/BLE/QR proximity: neither encryptionInfo nor origin — anti-replay is transport-level.
        assertFalse(requiresNonceVerification(isoRecord(encryptionInfo = null, origin = null)))
    }

    @Test
    fun dcApiIsoRecordWithEncryptionInfoRequiresNonceVerification() {
        assertTrue(
            requiresNonceVerification(
                isoRecord(encryptionInfo = ByteString(byteArrayOf(1, 2, 3)), origin = null)
            )
        )
    }

    @Test
    fun dcApiIsoRecordWithOriginRequiresNonceVerification() {
        assertTrue(
            requiresNonceVerification(
                isoRecord(encryptionInfo = null, origin = "https://verifier.example")
            )
        )
    }

    @Test
    fun dcApiIsoRecordWithBothRequiresNonceVerification() {
        assertTrue(
            requiresNonceVerification(
                isoRecord(
                    encryptionInfo = ByteString(byteArrayOf(1, 2, 3)),
                    origin = "https://verifier.example",
                )
            )
        )
    }

    @Test
    fun openId4VpRecordRequiresNonceVerification() {
        assertTrue(
            requiresNonceVerification(
                OpenID4VPPresentmentRecord(
                    vpToken = "{}",
                    vpRequest = "{}",
                    mdocSessionTranscript = null,
                )
            )
        )
    }
}
