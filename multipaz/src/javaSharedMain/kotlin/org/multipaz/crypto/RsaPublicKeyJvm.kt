package org.multipaz.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.PublicKey as JavaPublicKey

val RsaPublicKey.javaPublicKey: RSAPublicKey
    get() {
        val spec = RSAPublicKeySpec(BigInteger(1, modulus), BigInteger(1, publicExponent))
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePublic(spec) as RSAPublicKey
    }

fun JavaPublicKey.toRsaPublicKey(): RsaPublicKey {
    check(this is RSAPublicKey) { "Expected RSAPublicKey" }
    return RsaPublicKey(
        modulus = stripLeadingZero(this.modulus.toByteArray()),
        publicExponent = stripLeadingZero(this.publicExponent.toByteArray())
    )
}
