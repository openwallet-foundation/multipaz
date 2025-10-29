/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.securearea.software

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.prompt.requestPassphrase
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Hkdf
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.UnlockReason
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * An implementation of [SecureArea] in software.
 *
 * This implementation supports all the curves and algorithms defined by [SecureArea]
 * and also supports passphrase-protected keys. Key material is stored using the
 * [Storage] abstraction and passphrase-protected keys are encrypted using
 * [AES-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode)
 * with 256-bit keys with the key derived from the passphrase using
 * [HKDF](https://en.wikipedia.org/wiki/HKDF).
 *
 * On JVM and Android this is using [Crypto] and the algorithms and curves it implements.
 *
 * Use [SoftwareSecureArea.create] to create an instance of SoftwareSecureArea.
 */
class SoftwareSecureArea private constructor(private val storageTable: StorageTable) : SecureArea {
    override val identifier get() = IDENTIFIER

    override val displayName get() = "Software Secure Area"

    private val supportedAlgorithms_: List<Algorithm> by lazy {
        Algorithm.entries.filter {
            it.fullySpecified && it.curve != null && Crypto.supportedCurves.contains(it.curve)
        }
    }

    override val supportedAlgorithms: List<Algorithm>
        get() = supportedAlgorithms_

    override suspend fun createKey(
        alias: String?,
        createKeySettings: org.multipaz.securearea.CreateKeySettings
    ): SoftwareKeyInfo {
        if (alias != null) {
            // If the key with the given alias exists, it is silently overwritten.
            storageTable.delete(alias)
        }

        val settings = if (createKeySettings is SoftwareCreateKeySettings) {
            createKeySettings
        } else {
            // If user passed in a generic SecureArea.CreateKeySettings, honor them.
            val builder = SoftwareCreateKeySettings.Builder()
                .setAlgorithm(createKeySettings.algorithm)
            if (createKeySettings.validFrom != null && createKeySettings.validUntil != null) {
                builder.setValidityPeriod(
                    validFrom = createKeySettings.validFrom,
                    validUntil = createKeySettings.validUntil
                )
            }
            builder.build()
        }
        try {
            val privateKey = Crypto.createEcPrivateKey(settings.algorithm.curve!!)
            val encodedPublicKey = Cbor.encode(privateKey.publicKey.toCoseKey().toDataItem())
            val keyMetadata = if (settings.passphraseRequired) {
                val secretKey = derivePrivateKeyEncryptionKey(encodedPublicKey, settings.passphrase!!)
                val cleartextPrivateKey = Cbor.encode(privateKey.toCoseKey().toDataItem())
                val iv = Random.Default.nextBytes(12)
                val encryptedPrivateKey = Crypto.encrypt(
                    Algorithm.A128GCM,
                    secretKey,
                    iv,
                    cleartextPrivateKey
                )
                KeyMetadata(
                    algorithm = settings.algorithm,
                    passphraseRequired = true,
                    privateKey = null,
                    encryptedPrivateKey = ByteString(encryptedPrivateKey),
                    encryptedPrivateKeyIv = ByteString(iv),
                    encodedPublicKey = ByteString(encodedPublicKey),
                    passphraseConstraints = settings.passphraseConstraints
                )
            } else {
                KeyMetadata(
                    algorithm = settings.algorithm,
                    passphraseRequired = false,
                    privateKey = privateKey,
                    encryptedPrivateKey = null,
                    encryptedPrivateKeyIv = null,
                    encodedPublicKey = ByteString(encodedPublicKey),
                    passphraseConstraints = null
                )
            }
            val newAlias = storageTable.insert(
                key = alias,
                data = ByteString(keyMetadata.toCbor())
            )
            return getKeyInfo(newAlias)
        } catch (e: Exception) {
            // such as NoSuchAlgorithmException, CertificateException, InvalidAlgorithmParameterException, OperatorCreationException, IOException, NoSuchProviderException
            throw IllegalStateException("Unexpected exception", e)
        }
    }

    private fun derivePrivateKeyEncryptionKey(
        encodedPublicKey: ByteArray,
        passphrase: String
    ): ByteArray {
        val info = "ICPrivateKeyEncryption1".encodeToByteArray()
        return Hkdf.deriveKey(
            Algorithm.HMAC_SHA256,
            passphrase.encodeToByteArray(),
            encodedPublicKey,
            info,
            32
        )
    }

    override suspend fun deleteKey(alias: String) {
        storageTable.delete(alias)
    }

    private data class KeyData(
        val algorithm: Algorithm,
        val privateKey: EcPrivateKey,
    )

    private suspend fun loadKey(
        alias: String,
        keyUnlockData: KeyUnlockData?
    ): KeyData {
        var passphrase: String? = null
        if (keyUnlockData != null) {
            val unlockData = keyUnlockData as SoftwareKeyUnlockData
            passphrase = unlockData.passphrase
        }
        val data = storageTable.get(alias)
            ?: throw IllegalArgumentException("No key with given alias")
        val keyMetadata = KeyMetadata.fromCbor(data.toByteArray())
        val privateKey = if (keyMetadata.passphraseRequired) {
            if (passphrase == null) {
                throw KeyLockedException("No passphrase provided")
            }
            val encodedPublicKey = keyMetadata.encodedPublicKey.toByteArray()
            val encryptedPrivateKey = keyMetadata.encryptedPrivateKey!!.toByteArray()
            val iv = keyMetadata.encryptedPrivateKeyIv!!.toByteArray()
            val secretKey = derivePrivateKeyEncryptionKey(encodedPublicKey, passphrase)
            val encodedPrivateKey = try {
                Crypto.decrypt(Algorithm.A128GCM, secretKey, iv, encryptedPrivateKey)
            } catch (e: Exception) {
                throw KeyLockedException("Error decrypting private key - wrong passphrase?", e)
            }
            EcPrivateKey.fromDataItem(Cbor.decode(encodedPrivateKey))
        } else {
            keyMetadata.privateKey!!
        }
        return KeyData(keyMetadata.algorithm, privateKey)
    }

    /**
     * Gets the underlying private key.
     *
     * @param alias the alias for the key.
     * @param keyUnlockData unlock data, or `null`.
     * @return a [PrivateKey].
     * @throws KeyLockedException
     */
    suspend fun getPrivateKey(
        alias: String,
        keyUnlockData: KeyUnlockData?
    ): EcPrivateKey = loadKey(alias, keyUnlockData).privateKey

    private suspend fun<T> interactionHelper(
        alias: String,
        unlockReason: UnlockReason,
        op: suspend (unlockData: KeyUnlockData?) -> T,
    ): T =
        try {
            op.invoke(null)
        } catch (_: KeyLockedException) {
            val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
                ?: DefaultKeyUnlockDataProvider
            // DefaultKeyUnlockDataProvider.getKeyUnlockData checks passphrase/PIN before
            // returning it here, so retry loop is internal to the provider. We expect
            // other providers to do the same, as we do not want to subject non-interactive
            // providers to handle repeated calls when unlock data fails to unlock the key.
            val unlockData = unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                unlockReason = unlockReason
            )
            op.invoke(unlockData)
        }

    override suspend fun sign(
        alias: String,
        dataToSign: ByteArray,
        unlockReason: UnlockReason
    ): EcSignature {
        return interactionHelper(
            alias,
            unlockReason,
            op = { unlockData -> signNonInteractive(alias, dataToSign, unlockData) }
        )
    }

    private suspend fun signNonInteractive(
        alias: String,
        dataToSign: ByteArray,
        keyUnlockData: KeyUnlockData?
    ): EcSignature {
        val keyData = loadKey(alias, keyUnlockData)
        require(keyData.algorithm.isSigning) { "Key algorithm is not for Signing" }
        return Crypto.sign(keyData.privateKey, keyData.algorithm, dataToSign)
    }

    override suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: UnlockReason
    ): ByteArray {
        return interactionHelper(
            alias,
            unlockReason,
            op = { unlockData -> keyAgreementNonInteractive(alias, otherKey, unlockData) }
        )
    }

    private suspend fun keyAgreementNonInteractive(
        alias: String,
        otherKey: EcPublicKey,
        keyUnlockData: KeyUnlockData?
    ): ByteArray {
        val keyData = loadKey(alias, keyUnlockData)
        require(keyData.algorithm.isKeyAgreement) { "Key algorithm is not for Key Agreement" }
        return Crypto.keyAgreement(keyData.privateKey, otherKey)
    }

    override suspend fun getKeyInfo(alias: String): SoftwareKeyInfo {
        val data = storageTable.get(alias)
            ?: throw IllegalArgumentException("No key with the given alias '$alias'")
        val keyMetadata = KeyMetadata.fromCbor(data.toByteArray())
        val publicKey = EcPublicKey.fromDataItem(Cbor.decode(keyMetadata.encodedPublicKey.toByteArray()))
        return SoftwareKeyInfo(
            alias,
            publicKey,
            KeyAttestation(publicKey, null),
            keyMetadata.algorithm,
            keyMetadata.passphraseRequired,
            keyMetadata.passphraseConstraints
        )
    }

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        // Software keys are never invalidated.
        return false
    }

    object DefaultKeyUnlockDataProvider: KeyUnlockDataProvider {
        override suspend fun getKeyUnlockData(
            secureArea: SecureArea,
            alias: String,
            unlockReason: UnlockReason
        ): KeyUnlockData {
            secureArea as SoftwareSecureArea
            val keyInfo = secureArea.getKeyInfo(alias)
            val constraints = keyInfo.passphraseConstraints ?: PassphraseConstraints.NONE
            val promptModel = try {
                PromptModel.get(coroutineContext)
            } catch (_: PromptModelNotAvailableException) {
                throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
            }
            val humanReadable = promptModel.toHumanReadable(unlockReason, constraints)
            val passphrase = promptModel.requestPassphrase(
                title = humanReadable.title,
                subtitle = humanReadable.subtitle,
                passphraseConstraints = constraints,
                passphraseEvaluator = { enteredPassphrase: String ->
                    try {
                        secureArea.loadKey(alias, SoftwareKeyUnlockData(enteredPassphrase))
                        PassphraseEvaluation.OK
                    } catch (_: Throwable) {
                        // TODO: translations
                        PassphraseEvaluation.TryAgain
                    }
                }
            )
            if (passphrase == null) {
                throw KeyLockedException("User canceled passphrase prompt")
            }
            return SoftwareKeyUnlockData(passphrase)
        }
    }

    @CborSerializable(schemaHash = "6ifPnbV5Efd5Yf9NLMh4wXHWIVySzLaTeJqnGxrPuzI")
    internal data class KeyMetadata(
        val algorithm: Algorithm,
        val passphraseRequired: Boolean,
        val privateKey: EcPrivateKey?,
        val encryptedPrivateKey: ByteString?,
        val encryptedPrivateKeyIv: ByteString?,
        val encodedPublicKey: ByteString,  // store as encoded CoseKey
        val passphraseConstraints: PassphraseConstraints?
    ) {
        companion object
    }

    companion object {
        private const val TAG = "SoftwareSecureArea"

        const val IDENTIFIER = "SoftwareSecureArea"

        /**
         * Creates an instance of SoftwareSecureArea.
         *
         * @param storage the storage engine to use for storing key material.
         */
        suspend fun create(storage: Storage): SoftwareSecureArea {
            return SoftwareSecureArea(storage.getTable(tableSpec))
        }

        private val tableSpec = StorageTableSpec(
            name = "SoftwareSecureArea",
            supportPartitions = false,
            supportExpiration = false
        )
    }
}
