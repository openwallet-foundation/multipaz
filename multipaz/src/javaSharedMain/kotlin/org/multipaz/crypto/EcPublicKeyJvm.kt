package org.multipaz.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteStringBuilder
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

fun PublicKey.toEcPublicKey(curve: EcCurve): EcPublicKey =
    when (curve) {
        EcCurve.X25519 -> {
            val enc = this.encoded
            EcPublicKeyOkp(
                curve,
                enc.sliceArray(IntRange(X25519_X509_ENCODED_PREFIX.size, enc.size - 1))
            )
        }

        EcCurve.X448 -> {
            val enc = this.encoded
            EcPublicKeyOkp(
                curve,
                enc.sliceArray(IntRange(X448_X509_ENCODED_PREFIX.size, enc.size - 1))
            )
        }

        EcCurve.ED25519 -> {
            val enc = this.encoded
            EcPublicKeyOkp(
                curve,
                enc.sliceArray(IntRange(ED25519_X509_ENCODED_PREFIX.size, enc.size - 1))
            )
        }

        EcCurve.ED448 -> {
            val enc = this.encoded
            EcPublicKeyOkp(
                curve,
                enc.sliceArray(IntRange(ED448_X509_ENCODED_PREFIX.size, enc.size - 1))
            )
        }

        else -> {
            check(this is ECPublicKey)
            val keySizeOctets = (curve.bitSize + 7) / 8
            EcPublicKeyDoubleCoordinate(
                curve,
                BigIntegersAsUnsignedByteArray(keySizeOctets, this.w.affineX),
                BigIntegersAsUnsignedByteArray(keySizeOctets, this.w.affineY)
            )
        }
    }

private val ED25519_X509_ENCODED_PREFIX =
    byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
private val X25519_X509_ENCODED_PREFIX =
    byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
private val ED448_X509_ENCODED_PREFIX =
    byteArrayOf(0x30, 0x43, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x71, 0x03, 0x3a, 0x00)
private val X448_X509_ENCODED_PREFIX =
    byteArrayOf(0x30, 0x42, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6f, 0x03, 0x39, 0x00)

/**
 * Return the passed in value as an unsigned byte array of the specified length, padded with
 * leading zeros as necessary.
 *
 * @param length the fixed length of the result
 * @param value  the value to be converted.
 * @return a byte array padded to a fixed length with leading zeros.
 */
internal fun BigIntegersAsUnsignedByteArray(length: Int, value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    if (bytes.size == length) {
        return bytes
    }

    val start = if (bytes[0] == 0.toByte() && bytes.size != 1) 1 else 0
    val count = bytes.size - start

    if (count > length) {
        throw IllegalArgumentException("Standard length exceeded for value");
    }

    val tmp = ByteArray(length)
    System.arraycopy(bytes, start, tmp, tmp.size - count, count);
    return tmp;
}

val EcPublicKey.javaPublicKey: PublicKey
    get() = when (this) {
        is EcPublicKeyDoubleCoordinate -> {
            val keySizeOctets = (curve.bitSize + 7)/8
            check(x.size == keySizeOctets)
            check(y.size == keySizeOctets)
            val bx = BigInteger(1, x)
            val by = BigInteger(1, y)
            try {
                val params = AlgorithmParameters.getInstance("EC")
                params.init(ECGenParameterSpec(curve.SECGName))
                val ecParameters = params.getParameterSpec(ECParameterSpec::class.java)
                val ecPoint = ECPoint(bx, by)
                val keySpec = ECPublicKeySpec(ecPoint, ecParameters)
                val kf = KeyFactory.getInstance("EC")
                kf.generatePublic(keySpec)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalStateException("Unexpected error", e)
            }
        }

        is EcPublicKeyOkp -> {
            try {
                // Unfortunately we need to create an X509 encoded version of the public
                // key material, for simplicity we just use prefixes.
                val prefix: ByteArray
                val kf: KeyFactory
                when (curve) {
                    EcCurve.ED448 -> {
                        kf = KeyFactory.getInstance("EdDSA")
                        prefix = ED448_X509_ENCODED_PREFIX
                    }

                    EcCurve.ED25519 -> {
                        kf = KeyFactory.getInstance("EdDSA")
                        prefix = ED25519_X509_ENCODED_PREFIX
                    }

                    EcCurve.X25519 -> {
                        kf = KeyFactory.getInstance("XDH")
                        prefix = X25519_X509_ENCODED_PREFIX
                    }

                    EcCurve.X448 -> {
                        kf = KeyFactory.getInstance("XDH")
                        prefix = X448_X509_ENCODED_PREFIX
                    }

                    else -> throw IllegalArgumentException("Unsupported curve with id $curve")
                }
                val bsb = ByteStringBuilder()
                bsb.append(prefix)
                bsb.append(x)
                kf.generatePublic(X509EncodedKeySpec(bsb.toByteString().toByteArray()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // any exception, such as NoSuchAlgorithmException, InvalidKeySpecException, IOException, NoSuchProviderException
                throw IllegalStateException("Unexpected error", e)
            }
        }
    }

