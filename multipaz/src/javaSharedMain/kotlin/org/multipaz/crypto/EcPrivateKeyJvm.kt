package org.multipaz.crypto

import org.multipaz.asn1.OID
import org.multipaz.crypto.Crypto.fixupEcDsaPrivateKeyMaterial
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec

fun PrivateKey.toEcPrivateKey(publicKey: PublicKey, curve: EcCurve): EcPrivateKey =
    when (curve) {
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
        EcCurve.BRAINPOOLP256R1,
        EcCurve.BRAINPOOLP320R1,
        EcCurve.BRAINPOOLP384R1,
        EcCurve.BRAINPOOLP512R1 -> {
            val pub = publicKey.toEcPublicKey(curve) as EcPublicKeyDoubleCoordinate
            val priv = this as ECPrivateKey
            val fixedUpD = fixupEcDsaPrivateKeyMaterial(curve, priv.s.toByteArray())
            EcPrivateKeyDoubleCoordinate(curve, fixedUpD, pub.x, pub.y)
        }

        EcCurve.ED25519,
        EcCurve.ED448 -> {
            val pub = publicKey.toEcPublicKey(curve) as EcPublicKeyOkp
            val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(encoded)
            EcPrivateKeyOkp(curve, d, pub.x)
        }
        EcCurve.X25519,
        EcCurve.X448 -> {
            val pub = publicKey.toEcPublicKey(curve) as EcPublicKeyOkp
            val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(encoded)
            EcPrivateKeyOkp(curve, d, pub.x)
        }
    }

val EcPrivateKey.javaPrivateKey: PrivateKey
    get() = when (this.curve) {
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
        EcCurve.BRAINPOOLP256R1,
        EcCurve.BRAINPOOLP320R1,
        EcCurve.BRAINPOOLP384R1,
        EcCurve.BRAINPOOLP512R1 -> {
            val keyFactory = KeyFactory.getInstance("EC")
            val paramSpec = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec(this@javaPrivateKey.curve.SECGName))
            }.getParameterSpec(ECParameterSpec::class.java)
            val spec = ECPrivateKeySpec(BigInteger(1, this.d), paramSpec)
            keyFactory.generatePrivate(spec)
        }
        EcCurve.ED25519 -> {
            val spec = PKCS8EncodedKeySpec(generatePrivateKeyInfo(OID.ED25519.oid, this.d))
            KeyFactory.getInstance("Ed25519").generatePrivate(spec)
        }
        EcCurve.X25519 -> {
            val spec = PKCS8EncodedKeySpec(generatePrivateKeyInfo(OID.X25519.oid, this.d))
            KeyFactory.getInstance("X25519").generatePrivate(spec)
        }
        EcCurve.ED448 -> {
            val spec = PKCS8EncodedKeySpec(generatePrivateKeyInfo(OID.ED448.oid, this.d))
            KeyFactory.getInstance("Ed448").generatePrivate(spec)
        }
        EcCurve.X448 -> {
            val spec = PKCS8EncodedKeySpec(generatePrivateKeyInfo(OID.X448.oid, this.d))
            KeyFactory.getInstance("X448").generatePrivate(spec)
        }


    }