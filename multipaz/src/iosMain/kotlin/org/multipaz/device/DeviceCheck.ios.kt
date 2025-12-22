@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.device

import org.multipaz.SwiftBridge
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.securearea.SecureArea
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.io.bytestring.ByteString
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
actual object DeviceCheck {
    actual suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString
    ): DeviceAttestationResult {
        val nonce = Crypto.digest(Algorithm.SHA256, challenge.toByteArray())
        return suspendCoroutine { continuation ->
            SwiftBridge.generateDeviceAttestation(nonce.toNSData()) { keyId, blob, err ->
                if (err != null) {
                    continuation.resumeWithException(Exception())
                } else {
                    continuation.resume(DeviceAttestationResult(
                        keyId!!,
                        DeviceAttestationIos(blob!!.toByteString())
                    ))
                }
            }
        }
    }

    actual suspend fun generateAssertion(
        secureArea: SecureArea,
        deviceAttestationId: String,
        assertion: Assertion
    ): DeviceAssertion {
        val assertionData = assertion.toCbor()
        val digest = Crypto.digest(Algorithm.SHA256, assertionData)
        return suspendCoroutine { continuation ->
            SwiftBridge.generateDeviceAssertion(
                deviceAttestationId,
                digest.toNSData()
            ) { blob, err ->
                if (err != null) {
                    continuation.resumeWithException(Exception())
                } else {
                    continuation.resume(
                        DeviceAssertion(
                            platformAssertion = blob!!.toByteString(),
                            assertionData = ByteString(assertionData)
                        )
                    )
                }
            }
        }
    }
}

// TODO: b/393388152 - Never used can be removed?
//  private fun ByteString.toNSData(): NSData = toByteArray().toNSData()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteString(): ByteString {
    return ByteString(ByteArray(length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), bytes, length)
        }
    })
}
