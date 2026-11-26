package org.multipaz.securearea

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cose.Cose
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.HybridPublicKey
import org.multipaz.crypto.HybridSignature
import org.multipaz.crypto.MlDsaPrivateKey
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.MlDsaSignature
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.crypto.SecureByteString
import org.multipaz.prompt.IosPromptModel
import org.multipaz.prompt.Reason
import org.multipaz.securearea.SecureEnclaveSecureArea.BiometryState
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecureEnclaveSecureAreaTest {

    @Test
    fun testGetKeyInvalidatedNonExistentKeyThrows() = runTest {
        val storage = EphemeralStorage()
        val secureArea = SecureEnclaveSecureArea.create(storage)
        assertFailsWith<IllegalArgumentException> {
            secureArea.getKeyInvalidated("nonExistentKey")
        }
    }

    @Test
    fun testNonBiometryCurrentSetKeysNotInvalidatedWhenDomainStateChanges() = runTest {
        val storage = EphemeralStorage()
        val state1 = "domain-state-1".encodeToByteString()
        val state2 = "domain-state-2".encodeToByteString()
        var currentBiometryState: BiometryState = BiometryState.Enrolled(state1)

        val secureArea = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "default",
            getBiometryState = { currentBiometryState }
        )

        val settingsList = listOf(
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(false, emptySet())
                .build(),
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(true, setOf(SecureEnclaveUserAuthType.USER_PRESENCE))
                .build(),
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(true, setOf(SecureEnclaveUserAuthType.DEVICE_PASSCODE))
                .build(),
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(true, setOf(SecureEnclaveUserAuthType.BIOMETRY_ANY))
                .build()
        )

        val pubKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        val aliases = settingsList.mapIndexed { index, settings ->
            val alias = "key_$index"
            secureArea.insertKey(alias, settings, byteArrayOf(1, 2, 3), pubKey)
            assertFalse(secureArea.getKeyInvalidated(alias))
            alias
        }

        // Changing biometric enrollment or removing biometrics does not invalidate these keys
        currentBiometryState = BiometryState.Enrolled(state2)
        for (alias in aliases) {
            assertFalse(secureArea.getKeyInvalidated(alias))
        }

        currentBiometryState = BiometryState.NotEnrolled
        for (alias in aliases) {
            assertFalse(secureArea.getKeyInvalidated(alias))
        }
    }

    @Test
    fun testBiometryCurrentSetKeyInvalidatedWhenDomainStateChanges() = runTest {
        val storage = EphemeralStorage()
        val state1 = "domain-state-1".encodeToByteString()
        val state2 = "domain-state-2".encodeToByteString()
        var currentBiometryState: BiometryState = BiometryState.Enrolled(state1)

        val secureArea = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "default",
            getBiometryState = { currentBiometryState }
        )

        val pubKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        secureArea.insertKey(
            "signKey",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET)
                )
                .build(),
            byteArrayOf(1, 2, 3),
            pubKey
        )
        secureArea.insertKey(
            "kaKey",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(
                        SecureEnclaveUserAuthType.DEVICE_PASSCODE,
                        SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET
                    )
                )
                .build(),
            byteArrayOf(4, 5, 6),
            pubKey
        )

        // Initially valid
        assertFalse(secureArea.getKeyInvalidated("signKey"))
        assertFalse(secureArea.getKeyInvalidated("kaKey"))

        // Temporary lockout (BiometryState.Unavailable) must NOT report keys as invalidated
        currentBiometryState = BiometryState.Unavailable
        assertFalse(secureArea.getKeyInvalidated("signKey"))
        assertFalse(secureArea.getKeyInvalidated("kaKey"))

        // Biometric enrollment changes -> invalidated
        currentBiometryState = BiometryState.Enrolled(state2)
        assertTrue(secureArea.getKeyInvalidated("signKey"))
        assertTrue(secureArea.getKeyInvalidated("kaKey"))

        // getKeyInfo must still succeed on invalidated keys
        assertEquals("signKey", secureArea.getKeyInfo("signKey").alias)
        assertEquals("kaKey", secureArea.getKeyInfo("kaKey").alias)

        // Cryptographic operations must throw KeyInvalidatedException
        assertFailsWith<KeyInvalidatedException> {
            secureArea.sign("signKey", "hello".encodeToByteArray())
        }
        Crypto.createEcPrivateKey(EcCurve.P256).use { otherKey ->
            assertFailsWith<KeyInvalidatedException> {
                secureArea.keyAgreement("kaKey", otherKey.publicKey)
            }
        }

        // Biometrics completely unenrolled -> still invalidated
        currentBiometryState = BiometryState.NotEnrolled
        assertTrue(secureArea.getKeyInvalidated("signKey"))
        assertTrue(secureArea.getKeyInvalidated("kaKey"))
    }

    @Test
    fun testBiometryCurrentSetKeyCreatedWhenNotEnrolledIsInvalidated() = runTest {
        val storage = EphemeralStorage()
        var currentBiometryState: BiometryState = BiometryState.NotEnrolled

        val secureArea = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "default",
            getBiometryState = { currentBiometryState }
        )

        val pubKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        secureArea.insertKey(
            "keyCreatedWithoutBiometrics",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET)
                )
                .build(),
            byteArrayOf(1, 2, 3),
            pubKey
        )

        assertTrue(secureArea.getKeyInvalidated("keyCreatedWithoutBiometrics"))

        // Even if biometrics are enrolled later, the key remains invalidated
        currentBiometryState = BiometryState.Enrolled("domain-state-1".encodeToByteString())
        assertTrue(secureArea.getKeyInvalidated("keyCreatedWithoutBiometrics"))
        assertFailsWith<KeyInvalidatedException> {
            secureArea.sign("keyCreatedWithoutBiometrics", "hello".encodeToByteArray())
        }
    }

    @Test
    fun testLegacyMetadataWithoutBiometryDomainState() = runTest {
        val storage = EphemeralStorage()
        val table = storage.getTable(SecureEnclaveSecureArea.tableSpec)
        val secureArea = SecureEnclaveSecureArea(
            storageTable = table,
            partitionId = "default",
            getBiometryState = { BiometryState.Enrolled("domain-state-new".encodeToByteString()) }
        )

        // Create a key with Unavailable biometry state to simulate legacy metadata (biometryDomainState = null)
        val legacySecureArea = SecureEnclaveSecureArea(
            storageTable = table,
            partitionId = "default",
            getBiometryState = { BiometryState.Unavailable }
        )
        val pubKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        legacySecureArea.insertKey(
            "legacyKey",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET)
                )
                .build(),
            byteArrayOf(1, 2, 3),
            pubKey
        )

        // Verify biometryDomainState is absent in CBOR and getKeyInvalidated returns false
        val rawCbor = table.get("legacyKey", "default")!!.toByteArray()
        val map = Cbor.decode(rawCbor) as CborMap
        assertFalse(map.hasKey("biometryDomainState"))
        assertFalse(secureArea.getKeyInvalidated("legacyKey"))
    }

    @Test
    fun testDefaultKeyUnlockDataProviderUsesPromptModel() = runTest {
        val storage = EphemeralStorage()
        val secureArea = SecureEnclaveSecureArea.create(storage)
        val pubKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        secureArea.insertKey(
            "authKey",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(SecureEnclaveUserAuthType.USER_PRESENCE)
                )
                .build(),
            byteArrayOf(1, 2, 3),
            pubKey
        )

        // Without PromptModel in context, falls back to IosPromptModel.defaultConvertToHumanReadable
        val expectedReason = Reason.HumanReadable(
            title = "Sign credential request",
            subtitle = "Confirm with Face ID",
            requireConfirmation = false
        )
        val unlockDataList1 = secureArea.unlockKey("authKey", expectedReason)
        assertEquals(1, unlockDataList1.size)
        assertTrue(unlockDataList1[0] is SecureEnclaveKeyUnlockData)

        // With custom PromptModel in context, invokes PromptModel.toHumanReadable
        var capturedReason: Reason? = null
        val customPromptModel = IosPromptModel.Builder { reason, _ ->
            capturedReason = reason
            Reason.HumanReadable(
                title = "Custom Title",
                subtitle = "",
                requireConfirmation = false
            )
        }.build()
        val unlockDataList2 = withContext(customPromptModel) {
            secureArea.unlockKey("authKey", expectedReason)
        }
        assertEquals(1, unlockDataList2.size)
        assertTrue(unlockDataList2[0] is SecureEnclaveKeyUnlockData)
        assertEquals(expectedReason, capturedReason)
    }

    private class FakeBackend(
        override val isPqcSupported: Boolean = true,
        override val isRunningOnSimulator: Boolean = false,
        private val useRealCrypto: Boolean = false,
    ) : SecureEnclaveSecureArea.Backend {
        val ecKeys = mutableMapOf<String, EcPrivateKey>()
        val mlDsaKeys = mutableMapOf<String, MlDsaPrivateKey>()
        var nextId = 1
        var lastEcFlags: Long? = null
        var lastMlDsaFlags: Long? = null
        var lastPqContext: ByteArray? = null
        var lastPqUnlockData: SecureEnclaveKeyUnlockData? = null
        var lastEcUnlockData: SecureEnclaveKeyUnlockData? = null

        override suspend fun createEcPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, EcPublicKey> {
            lastEcFlags = accessControlCreateFlags
            val blob = "ec_${nextId++}".encodeToByteArray()
            val priv = Crypto.createEcPrivateKey(EcCurve.P256)
            val pub = priv.publicKey
            if (useRealCrypto) {
                ecKeys[blob.decodeToString()] = priv
            } else {
                priv.close()
            }
            return blob to pub
        }

        override suspend fun ecSign(
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): EcSignature {
            lastEcUnlockData = keyUnlockData
            return if (useRealCrypto) {
                val key = ecKeys.getValue(keyBlob.decodeToString())
                Crypto.sign(key, Algorithm.ESP256, message)
            } else {
                EcSignature(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 })
            }
        }

        override suspend fun ecKeyAgreement(
            keyBlob: ByteArray,
            otherKey: EcPublicKey,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString = throw UnsupportedOperationException()

        override suspend fun createMlDsaPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlDsaPublicKey> {
            lastMlDsaFlags = accessControlCreateFlags
            val blob = "mldsa_${nextId++}".encodeToByteArray()
            return if (useRealCrypto) {
                val priv = Crypto.createMlDsaPrivateKey(algorithm)
                mlDsaKeys[blob.decodeToString()] = priv
                blob to priv.publicKey
            } else {
                val size = when (algorithm) {
                    Algorithm.ML_DSA_65 -> 1952
                    Algorithm.ML_DSA_87 -> 2592
                    else -> throw IllegalArgumentException("Unsupported $algorithm")
                }
                blob to MlDsaPublicKey(algorithm, ByteString(ByteArray(size) { 0x11 }))
            }
        }

        override suspend fun mlDsaSign(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?,
            context: ByteArray
        ): MlDsaSignature {
            lastPqUnlockData = keyUnlockData
            lastPqContext = context
            return if (useRealCrypto) {
                val key = mlDsaKeys.getValue(keyBlob.decodeToString())
                Crypto.sign(key, algorithm, message, context)
            } else {
                MlDsaSignature(ByteArray(3309) { 0x22 })
            }
        }

        override suspend fun createMlKemPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlKemPublicKey> = throw UnsupportedOperationException()

        override suspend fun mlKemDecapsulate(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            ciphertext: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString = throw UnsupportedOperationException()

        fun close() {
            ecKeys.values.forEach { it.close() }
            ecKeys.clear()
            mlDsaKeys.values.forEach { it.close() }
            mlDsaKeys.clear()
        }
    }

    @Test
    fun testSupportedAlgorithmsWithAndWithoutPqc() = runTest {
        val storage = EphemeralStorage()
        val withPqc = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "withPqc",
            getBiometryState = { BiometryState.Unavailable },
            backend = FakeBackend(isPqcSupported = true)
        )
        assertTrue(Algorithm.ML_DSA_65_ES256 in withPqc.supportedAlgorithms)
        for (unsupported in listOf(
            Algorithm.ML_DSA_44_ES256,
            Algorithm.ML_DSA_65_ES384,
            Algorithm.ML_DSA_87_ES384,
            Algorithm.ML_DSA_44_ED25519
        )) {
            assertFalse(unsupported in withPqc.supportedAlgorithms)
            assertFailsWith<IllegalArgumentException> {
                withPqc.createKey(
                    "bad_$unsupported",
                    SecureEnclaveCreateKeySettings.Builder()
                        .setAlgorithm(unsupported)
                        .build()
                )
            }
        }

        val withoutPqc = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "withoutPqc",
            getBiometryState = { BiometryState.Unavailable },
            backend = FakeBackend(isPqcSupported = false)
        )
        assertFalse(Algorithm.ML_DSA_65_ES256 in withoutPqc.supportedAlgorithms)
        assertFailsWith<IllegalArgumentException> {
            withoutPqc.createKey(
                "hybridPreIos26",
                SecureEnclaveCreateKeySettings.Builder()
                    .setAlgorithm(Algorithm.ML_DSA_65_ES256)
                    .build()
            )
        }
    }

    @Test
    fun testHybridKeyMetadataInvalidationAndSingleUnlock() = runTest {
        val storage = EphemeralStorage()
        val state1 = "domain-state-1".encodeToByteString()
        val state2 = "domain-state-2".encodeToByteString()
        var currentBiometryState: BiometryState = BiometryState.Enrolled(state1)
        val backend = FakeBackend(isPqcSupported = true, isRunningOnSimulator = false)

        val secureArea = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "default",
            getBiometryState = { currentBiometryState },
            backend = backend
        )

        val keyInfo = secureArea.createKey(
            "hybridKey",
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ML_DSA_65_ES256)
                .setUserAuthenticationRequired(
                    true,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET)
                )
                .build()
        )
        assertEquals(Algorithm.ML_DSA_65_ES256, keyInfo.algorithm)
        assertTrue(keyInfo.publicKey is HybridPublicKey)
        assertNull(keyInfo.attestation.certChain)
        assertEquals(backend.lastMlDsaFlags, backend.lastEcFlags)
        assertTrue((backend.lastEcFlags ?: 0L) != 0L)

        // Re-open with a fresh cache and verify a single unlock + single LAContext is reused
        val reopened = SecureEnclaveSecureArea(
            storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
            partitionId = "default",
            getBiometryState = { currentBiometryState },
            backend = backend
        )
        var unlockCalls = 0
        val countingProvider = object : KeyUnlockDataProvider {
            override suspend fun getKeyUnlockData(
                secureArea: SecureArea,
                alias: String,
                algorithm: Algorithm,
                unlockReason: Reason
            ): KeyUnlockData {
                unlockCalls++
                return SecureEnclaveKeyUnlockData(secureArea as SecureEnclaveSecureArea, alias)
            }
        }
        val sig = withContext(countingProvider) {
            reopened.sign("hybridKey", "hello hybrid".encodeToByteArray())
        }
        assertTrue(sig is HybridSignature)
        assertEquals(1, unlockCalls)
        assertSame(backend.lastPqUnlockData, backend.lastEcUnlockData)
        assertSame(
            backend.lastPqUnlockData?.authenticationContext,
            backend.lastEcUnlockData?.authenticationContext
        )
        assertContentEquals(
            "COMPSIG-MLDSA65-ECDSA-P256-SHA512".encodeToByteArray(),
            backend.lastPqContext
        )

        // Preloaded unlock flow must also invoke getKeyUnlockData / unlockKey cleanly
        val preloaded = reopened.unlockKey("hybridKey")
        assertEquals(1, preloaded.size)
        val preloadedProvider = buildPreloadedKeyUnlockDataProvider {
            add(preloaded)
        }
        val preloadedSig = withContext(preloadedProvider) {
            reopened.sign("hybridKey", "preloaded payload".encodeToByteArray())
        }
        assertTrue(preloadedSig is HybridSignature)
        assertSame<KeyUnlockData?>(preloaded[0], backend.lastPqUnlockData)
        assertSame<KeyUnlockData?>(preloaded[0], backend.lastEcUnlockData)

        // Biometric enrollment change invalidates the hybrid key
        currentBiometryState = BiometryState.Enrolled(state2)
        assertTrue(reopened.getKeyInvalidated("hybridKey"))
        assertFailsWith<KeyInvalidatedException> {
            withContext(countingProvider) {
                reopened.sign("hybridKey", "should fail".encodeToByteArray())
            }
        }
    }

    @Test
    fun testHybridCreateAndSignCryptographicRoundTrip() = runTest {
        if (!Crypto.secureEnclaveIsPqcSupported) {
            return@runTest
        }
        val storage = EphemeralStorage()
        val backend = FakeBackend(isPqcSupported = true, isRunningOnSimulator = false, useRealCrypto = true)
        try {
            val secureArea = SecureEnclaveSecureArea(
                storageTable = storage.getTable(SecureEnclaveSecureArea.tableSpec),
                partitionId = "default",
                getBiometryState = { BiometryState.Unavailable },
                backend = backend
            )
            val keyInfo = secureArea.createKey(
                "realHybridKey",
                SecureEnclaveCreateKeySettings.Builder()
                    .setAlgorithm(Algorithm.ML_DSA_65_ES256)
                    .build()
            )
            val publicKey = keyInfo.publicKey as HybridPublicKey
            val message = "SecureEnclave ML-DSA-65-ES256 roundtrip".encodeToByteArray()
            val sig = secureArea.sign("realHybridKey", message) as HybridSignature
            Crypto.checkSignature(publicKey, message, Algorithm.ML_DSA_65_ES256, sig)

            val signingKey = AsymmetricKey.anonymous(secureArea, "realHybridKey")
            val coseSign1 = Cose.coseSign1Sign(
                signingKey = signingKey,
                message = message,
                includeMessageInPayload = true
            )
            Cose.coseSign1Check(
                publicKey = publicKey,
                detachedData = null,
                signature = coseSign1,
                signatureAlgorithm = Algorithm.ML_DSA_65_ES256
            )
        } finally {
            backend.close()
        }
    }
}
