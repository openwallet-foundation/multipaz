package org.multipaz.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Reflection-based helper to access Bouncy Castle PQC (ML-DSA and ML-KEM) classes without a
 * compile-time or mandatory runtime dependency on Bouncy Castle.
 */
internal object BouncyCastlePqc {

    val isAvailable: Boolean = try {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator")
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    private val mldsaParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters")
    }
    private val mldsaKeyGenParamsConstructor by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters")
            .getConstructor(SecureRandom::class.java, mldsaParamsClass)
    }
    private val mldsaKeyPairGeneratorClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator")
    }
    private val mldsaKeyPairGeneratorInitMethod by lazy {
        mldsaKeyPairGeneratorClass.getMethod(
            "init",
            Class.forName("org.bouncycastle.crypto.KeyGenerationParameters")
        )
    }
    private val mldsaKeyPairGeneratorGenerateMethod by lazy {
        mldsaKeyPairGeneratorClass.getMethod("generateKeyPair")
    }
    private val asymmetricCipherKeyPairClass by lazy {
        Class.forName("org.bouncycastle.crypto.AsymmetricCipherKeyPair")
    }
    private val keyPairGetPublicMethod by lazy {
        asymmetricCipherKeyPairClass.getMethod("getPublic")
    }
    private val keyPairGetPrivateMethod by lazy {
        asymmetricCipherKeyPairClass.getMethod("getPrivate")
    }
    private val mldsaPubParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters")
    }
    private val mldsaPubParamsConstructor by lazy {
        mldsaPubParamsClass.getConstructor(mldsaParamsClass, ByteArray::class.java)
    }
    private val mldsaPubParamsGetEncodedMethod by lazy {
        mldsaPubParamsClass.getMethod("getEncoded")
    }
    private val mldsaPrivParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters")
    }
    private val mldsaPrivParamsConstructor by lazy {
        mldsaPrivParamsClass.getConstructor(mldsaParamsClass, ByteArray::class.java)
    }
    private val mldsaPrivParamsGetEncodedMethod by lazy {
        mldsaPrivParamsClass.getMethod("getEncoded")
    }
    private val mldsaPrivParamsGetSeedMethod by lazy {
        mldsaPrivParamsClass.getMethod("getSeed")
    }
    private val parametersWithContextConstructor by lazy {
        Class.forName("org.bouncycastle.crypto.params.ParametersWithContext").getConstructor(
            Class.forName("org.bouncycastle.crypto.CipherParameters"),
            ByteArray::class.java
        )
    }
    private val mldsaSignerClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mldsa.MLDSASigner")
    }
    private val mldsaSignerInitMethod by lazy {
        mldsaSignerClass.getMethod(
            "init",
            Boolean::class.javaPrimitiveType,
            Class.forName("org.bouncycastle.crypto.CipherParameters")
        )
    }
    private val mldsaSignerUpdateMethod by lazy {
        mldsaSignerClass.getMethod(
            "update",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
    }
    private val mldsaSignerGenerateSignatureMethod by lazy {
        mldsaSignerClass.getMethod("generateSignature")
    }
    private val mldsaSignerVerifySignatureMethod by lazy {
        mldsaSignerClass.getMethod("verifySignature", ByteArray::class.java)
    }

    private val mlkemParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters")
    }
    private val mlkemKeyGenParamsConstructor by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters")
            .getConstructor(SecureRandom::class.java, mlkemParamsClass)
    }
    private val mlkemKeyPairGeneratorClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator")
    }
    private val mlkemKeyPairGeneratorInitMethod by lazy {
        mlkemKeyPairGeneratorClass.getMethod(
            "init",
            Class.forName("org.bouncycastle.crypto.KeyGenerationParameters")
        )
    }
    private val mlkemKeyPairGeneratorGenerateMethod by lazy {
        mlkemKeyPairGeneratorClass.getMethod("generateKeyPair")
    }
    private val mlkemPubParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters")
    }
    private val mlkemPubParamsConstructor by lazy {
        mlkemPubParamsClass.getConstructor(mlkemParamsClass, ByteArray::class.java)
    }
    private val mlkemPubParamsGetEncodedMethod by lazy {
        mlkemPubParamsClass.getMethod("getEncoded")
    }
    private val mlkemPrivParamsClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters")
    }
    private val mlkemPrivParamsConstructor by lazy {
        mlkemPrivParamsClass.getConstructor(mlkemParamsClass, ByteArray::class.java)
    }
    private val mlkemPrivParamsGetEncodedMethod by lazy {
        mlkemPrivParamsClass.getMethod("getEncoded")
    }
    private val mlkemPrivParamsGetSeedMethod by lazy {
        mlkemPrivParamsClass.getMethod("getSeed")
    }
    private val mlkemPrivParamsGetPublicKeyMethod by lazy {
        mlkemPrivParamsClass.getMethod("getPublicKey")
    }
    private val mlkemGeneratorClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator")
    }
    private val mlkemGeneratorConstructor by lazy {
        mlkemGeneratorClass.getConstructor(SecureRandom::class.java)
    }
    private val mlkemGeneratorGenerateEncapsulatedMethod by lazy {
        mlkemGeneratorClass.getMethod(
            "generateEncapsulated",
            Class.forName("org.bouncycastle.crypto.params.AsymmetricKeyParameter")
        )
    }
    private val mlkemGeneratorInternalGenerateEncapsulatedMethod by lazy {
        mlkemGeneratorClass.getMethod(
            "internalGenerateEncapsulated",
            Class.forName("org.bouncycastle.crypto.params.AsymmetricKeyParameter"),
            ByteArray::class.java
        )
    }
    private val customNamedCurvesGetByNameMethod by lazy {
        Class.forName("org.bouncycastle.crypto.ec.CustomNamedCurves")
            .getMethod("getByName", String::class.java)
    }
    private val x9EcParametersClass by lazy {
        Class.forName("org.bouncycastle.asn1.x9.X9ECParameters")
    }
    private val x9EcParametersGetGMethod by lazy {
        x9EcParametersClass.getMethod("getG")
    }
    private val x9EcParametersGetNMethod by lazy {
        x9EcParametersClass.getMethod("getN")
    }
    private val ecPointClass by lazy {
        Class.forName("org.bouncycastle.math.ec.ECPoint")
    }
    private val ecPointMultiplyMethod by lazy {
        ecPointClass.getMethod("multiply", BigInteger::class.java)
    }
    private val ecPointNormalizeMethod by lazy {
        ecPointClass.getMethod("normalize")
    }
    private val ecPointGetAffineXCoordMethod by lazy {
        ecPointClass.getMethod("getAffineXCoord")
    }
    private val ecPointGetAffineYCoordMethod by lazy {
        ecPointClass.getMethod("getAffineYCoord")
    }
    private val ecFieldElementGetEncodedMethod by lazy {
        Class.forName("org.bouncycastle.math.ec.ECFieldElement")
            .getMethod("getEncoded")
    }
    private val x25519PrivParamsClass by lazy {
        Class.forName("org.bouncycastle.crypto.params.X25519PrivateKeyParameters")
    }
    private val x25519PrivParamsConstructor by lazy {
        x25519PrivParamsClass.getConstructor(ByteArray::class.java, Int::class.javaPrimitiveType)
    }
    private val x25519PrivParamsGeneratePublicKeyMethod by lazy {
        x25519PrivParamsClass.getMethod("generatePublicKey")
    }
    private val x25519PubParamsGetEncodedMethod by lazy {
        Class.forName("org.bouncycastle.crypto.params.X25519PublicKeyParameters")
            .getMethod("getEncoded")
    }
    private val secretWithEncapsulationClass by lazy {
        Class.forName("org.bouncycastle.crypto.SecretWithEncapsulation")
    }
    private val secretWithEncapsulationGetSecretMethod by lazy {
        secretWithEncapsulationClass.getMethod("getSecret")
    }
    private val secretWithEncapsulationGetEncapsulationMethod by lazy {
        secretWithEncapsulationClass.getMethod("getEncapsulation")
    }
    private val mlkemExtractorClass by lazy {
        Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor")
    }
    private val mlkemExtractorConstructor by lazy {
        mlkemExtractorClass.getConstructor(mlkemPrivParamsClass)
    }
    private val mlkemExtractorExtractSecretMethod by lazy {
        mlkemExtractorClass.getMethod("extractSecret", ByteArray::class.java)
    }

    private fun algorithmToMlDsaParameters(algorithm: Algorithm): Any {
        val fieldName = when (algorithm) {
            Algorithm.ML_DSA_44 -> "ml_dsa_44"
            Algorithm.ML_DSA_65 -> "ml_dsa_65"
            Algorithm.ML_DSA_87 -> "ml_dsa_87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        return mldsaParamsClass.getField(fieldName).get(null)!!
    }

    private fun algorithmToMlKemParameters(algorithm: Algorithm): Any {
        val fieldName = when (algorithm) {
            Algorithm.ML_KEM_512 -> "ml_kem_512"
            Algorithm.ML_KEM_768 -> "ml_kem_768"
            Algorithm.ML_KEM_1024 -> "ml_kem_1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm $algorithm")
        }
        return mlkemParamsClass.getField(fieldName).get(null)!!
    }

    private inline fun <T> wrapInvocation(block: () -> T): T {
        try {
            return block()
        } catch (e: InvocationTargetException) {
            val target = e.targetException
            if (target is CancellationException) throw target
            if (target is SignatureVerificationException) throw target
            if (target is IllegalArgumentException) throw target
            throw IllegalStateException("Bouncy Castle PQC operation failed", target)
        }
    }

    fun createMlDsaPrivateKey(algorithm: Algorithm): MlDsaPrivateKey = wrapInvocation {
        val params = algorithmToMlDsaParameters(algorithm)
        val keyGenParams = mldsaKeyGenParamsConstructor.newInstance(SecureRandom(), params)
        val keyGen = mldsaKeyPairGeneratorClass.getDeclaredConstructor().newInstance()
        mldsaKeyPairGeneratorInitMethod.invoke(keyGen, keyGenParams)
        val keyPair = mldsaKeyPairGeneratorGenerateMethod.invoke(keyGen)
        val pubParams = keyPairGetPublicMethod.invoke(keyPair)
        val privParams = keyPairGetPrivateMethod.invoke(keyPair)
        val pubEncoded = mldsaPubParamsGetEncodedMethod.invoke(pubParams) as ByteArray
        val privSeed = (mldsaPrivParamsGetSeedMethod.invoke(privParams) as ByteArray?)
            ?: (mldsaPrivParamsGetEncodedMethod.invoke(privParams) as ByteArray)
        val publicKey = MlDsaPublicKey(algorithm, ByteString(pubEncoded))
        try {
            MlDsaPrivateKey(algorithm, ByteString(privSeed), publicKey)
        } finally {
            privSeed.secureZero()
        }
    }

    fun sign(
        key: MlDsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray,
        context: ByteArray = byteArrayOf()
    ): MlDsaSignature = wrapInvocation {
        require(signatureAlgorithm == key.algorithm) {
            "Signature algorithm $signatureAlgorithm doesn't match key algorithm ${key.algorithm}"
        }
        require(context.size <= 255) {
            "ML-DSA context length must be at most 255 bytes, got ${context.size}"
        }
        val params = algorithmToMlDsaParameters(key.algorithm)
        val keyBytes = key.encoded.toByteArray()
        val privParams = try {
            mldsaPrivParamsConstructor.newInstance(params, keyBytes)
        } finally {
            keyBytes.secureZero()
        }
        val initParams = if (context.isEmpty()) {
            privParams
        } else {
            parametersWithContextConstructor.newInstance(privParams, context)
        }
        val signer = mldsaSignerClass.getDeclaredConstructor().newInstance()
        mldsaSignerInitMethod.invoke(signer, true, initParams)
        mldsaSignerUpdateMethod.invoke(signer, message, 0, message.size)
        val signature = mldsaSignerGenerateSignatureMethod.invoke(signer) as ByteArray
        MlDsaSignature(signature)
    }

    fun checkSignature(
        publicKey: MlDsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: MlDsaSignature,
        context: ByteArray = byteArrayOf()
    ): Unit = wrapInvocation {
        require(algorithm == publicKey.algorithm) {
            "Signature algorithm $algorithm doesn't match key algorithm ${publicKey.algorithm}"
        }
        require(context.size <= 255) {
            "ML-DSA context length must be at most 255 bytes, got ${context.size}"
        }
        val params = algorithmToMlDsaParameters(publicKey.algorithm)
        val pubParams = mldsaPubParamsConstructor.newInstance(params, publicKey.encoded.toByteArray())
        val initParams = if (context.isEmpty()) {
            pubParams
        } else {
            parametersWithContextConstructor.newInstance(pubParams, context)
        }
        val verifier = mldsaSignerClass.getDeclaredConstructor().newInstance()
        mldsaSignerInitMethod.invoke(verifier, false, initParams)
        mldsaSignerUpdateMethod.invoke(verifier, message, 0, message.size)
        val verified = mldsaSignerVerifySignatureMethod.invoke(verifier, signature.signature) as Boolean
        if (!verified) {
            throw SignatureVerificationException("ML-DSA signature verification failed")
        }
    }

    fun createMlKemPrivateKey(algorithm: Algorithm): MlKemPrivateKey = wrapInvocation {
        val params = algorithmToMlKemParameters(algorithm)
        val keyGenParams = mlkemKeyGenParamsConstructor.newInstance(SecureRandom(), params)
        val keyGen = mlkemKeyPairGeneratorClass.getDeclaredConstructor().newInstance()
        mlkemKeyPairGeneratorInitMethod.invoke(keyGen, keyGenParams)
        val keyPair = mlkemKeyPairGeneratorGenerateMethod.invoke(keyGen)
        val pubParams = keyPairGetPublicMethod.invoke(keyPair)
        val privParams = keyPairGetPrivateMethod.invoke(keyPair)
        val pubEncoded = mlkemPubParamsGetEncodedMethod.invoke(pubParams) as ByteArray
        val privSeed = (mlkemPrivParamsGetSeedMethod.invoke(privParams) as ByteArray?)
            ?: (mlkemPrivParamsGetEncodedMethod.invoke(privParams) as ByteArray)
        val publicKey = MlKemPublicKey(algorithm, ByteString(pubEncoded))
        try {
            MlKemPrivateKey(algorithm, ByteString(privSeed), publicKey)
        } finally {
            privSeed.secureZero()
        }
    }

    fun createMlKemPrivateKeyFromSeed(
        algorithm: Algorithm,
        seed: ByteArray
    ): MlKemPrivateKey = wrapInvocation {
        require(seed.size == 64) { "ML-KEM seed must be 64 bytes, got ${seed.size}" }
        val params = algorithmToMlKemParameters(algorithm)
        val privParams = mlkemPrivParamsConstructor.newInstance(params, seed)
        val pubEncoded = mlkemPrivParamsGetPublicKeyMethod.invoke(privParams) as ByteArray
        val publicKey = MlKemPublicKey(algorithm, ByteString(pubEncoded))
        MlKemPrivateKey(algorithm, ByteString(seed), publicKey)
    }

    fun kemEncapsulate(
        recipientPublicKey: MlKemPublicKey
    ): KemResult = wrapInvocation {
        val params = algorithmToMlKemParameters(recipientPublicKey.algorithm)
        val pubParams = mlkemPubParamsConstructor.newInstance(params, recipientPublicKey.encoded.toByteArray())
        val generator = mlkemGeneratorConstructor.newInstance(SecureRandom())
        val result = mlkemGeneratorGenerateEncapsulatedMethod.invoke(generator, pubParams)
        val secret = secretWithEncapsulationGetSecretMethod.invoke(result) as ByteArray
        val encapsulation = secretWithEncapsulationGetEncapsulationMethod.invoke(result) as ByteArray
        val sharedSecret = SecureByteString(secret)
        secret.secureZero()
        KemResult(
            sharedSecret = sharedSecret,
            ciphertext = encapsulation
        )
    }

    fun kemEncapsulateDerand(
        recipientPublicKey: MlKemPublicKey,
        randomness: ByteArray
    ): KemResult = wrapInvocation {
        require(randomness.size == 32) {
            "ML-KEM encapsulation randomness must be 32 bytes, got ${randomness.size}"
        }
        val params = algorithmToMlKemParameters(recipientPublicKey.algorithm)
        val pubParams = mlkemPubParamsConstructor.newInstance(params, recipientPublicKey.encoded.toByteArray())
        val generator = mlkemGeneratorConstructor.newInstance(SecureRandom())
        val result = mlkemGeneratorInternalGenerateEncapsulatedMethod.invoke(generator, pubParams, randomness)
        val secret = secretWithEncapsulationGetSecretMethod.invoke(result) as ByteArray
        val encapsulation = secretWithEncapsulationGetEncapsulationMethod.invoke(result) as ByteArray
        val sharedSecret = SecureByteString(secret)
        secret.secureZero()
        KemResult(
            sharedSecret = sharedSecret,
            ciphertext = encapsulation
        )
    }

    fun kemDecapsulate(
        key: MlKemPrivateKey,
        ciphertext: ByteArray
    ): SecureByteString = wrapInvocation {
        val params = algorithmToMlKemParameters(key.algorithm)
        val keyBytes = key.encoded.toByteArray()
        val privParams = try {
            mlkemPrivParamsConstructor.newInstance(params, keyBytes)
        } finally {
            keyBytes.secureZero()
        }
        val extractor = mlkemExtractorConstructor.newInstance(privParams)
        val secret = mlkemExtractorExtractSecretMethod.invoke(extractor, ciphertext) as ByteArray
        val sharedSecret = SecureByteString(secret)
        secret.secureZero()
        sharedSecret
    }

    fun createEcPrivateKeyFromScalar(
        curve: EcCurve,
        scalar: ByteArray
    ): EcPrivateKey = wrapInvocation {
        when (curve) {
            EcCurve.X25519 -> {
                require(scalar.size == 32) { "X25519 scalar must be 32 bytes, got ${scalar.size}" }
                val privParams = x25519PrivParamsConstructor.newInstance(scalar, 0)
                val pubParams = x25519PrivParamsGeneratePublicKeyMethod.invoke(privParams)
                val pubBytes = x25519PubParamsGetEncodedMethod.invoke(pubParams) as ByteArray
                EcPrivateKeyOkp(curve = curve, d = scalar.copyOf(), x = pubBytes)
            }
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521 -> {
                val expectedSize = (curve.bitSize + 7) / 8
                require(scalar.size == expectedSize) {
                    "EC scalar for ${curve.name} must be $expectedSize bytes, got ${scalar.size}"
                }
                val x9Params = customNamedCurvesGetByNameMethod.invoke(null, curve.SECGName)
                    ?: throw IllegalArgumentException("Unsupported curve ${curve.name}")
                val order = x9EcParametersGetNMethod.invoke(x9Params) as BigInteger
                val s = BigInteger(1, scalar)
                require(s > BigInteger.ZERO && s < order) {
                    "EC scalar out of range [1, n-1] for ${curve.name}"
                }
                val g = x9EcParametersGetGMethod.invoke(x9Params)
                val point = ecPointNormalizeMethod.invoke(ecPointMultiplyMethod.invoke(g, s))
                val x = ecFieldElementGetEncodedMethod.invoke(ecPointGetAffineXCoordMethod.invoke(point)) as ByteArray
                val y = ecFieldElementGetEncodedMethod.invoke(ecPointGetAffineYCoordMethod.invoke(point)) as ByteArray
                EcPrivateKeyDoubleCoordinate(curve = curve, d = scalar.copyOf(), x = x, y = y)
            }
            else -> throw IllegalArgumentException("Unsupported curve for scalar derivation: ${curve.name}")
        }
    }
}
