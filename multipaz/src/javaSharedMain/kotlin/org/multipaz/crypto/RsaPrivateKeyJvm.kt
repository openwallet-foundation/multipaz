package org.multipaz.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPrivateKeySpec
import java.security.PrivateKey as JavaPrivateKey

val RsaPrivateKey.javaPrivateKey: RSAPrivateKey
    get() {
        val kf = KeyFactory.getInstance("RSA")
        return if (p != null && q != null && dp != null && dq != null && qInv != null) {
            val spec = RSAPrivateCrtKeySpec(
                BigInteger(1, modulus),
                BigInteger(1, publicExponent),
                BigInteger(1, privateExponent),
                BigInteger(1, p),
                BigInteger(1, q),
                BigInteger(1, dp),
                BigInteger(1, dq),
                BigInteger(1, qInv)
            )
            kf.generatePrivate(spec) as RSAPrivateKey
        } else {
            val spec = RSAPrivateKeySpec(
                BigInteger(1, modulus),
                BigInteger(1, privateExponent)
            )
            kf.generatePrivate(spec) as RSAPrivateKey
        }
    }

fun JavaPrivateKey.toRsaPrivateKey(publicKey: RsaPublicKey? = null): RsaPrivateKey {
    return if (this is RSAPrivateCrtKey) {
        val pub = publicKey ?: RsaPublicKey(
            modulus = stripLeadingZero(this.modulus.toByteArray()),
            publicExponent = stripLeadingZero(this.publicExponent.toByteArray())
        )
        RsaPrivateKey(
            publicKey = pub,
            privateExponent = stripLeadingZero(this.privateExponent.toByteArray()),
            p = stripLeadingZero(this.primeP.toByteArray()),
            q = stripLeadingZero(this.primeQ.toByteArray()),
            dp = stripLeadingZero(this.primeExponentP.toByteArray()),
            dq = stripLeadingZero(this.primeExponentQ.toByteArray()),
            qInv = stripLeadingZero(this.crtCoefficient.toByteArray())
        )
    } else if (this is RSAPrivateKey) {
        requireNotNull(publicKey) { "publicKey must be provided when converting non-CRT RSAPrivateKey" }
        RsaPrivateKey(
            publicKey = publicKey,
            privateExponent = stripLeadingZero(this.privateExponent.toByteArray())
        )
    } else {
        throw IllegalArgumentException("Expected RSAPrivateKey")
    }
}
