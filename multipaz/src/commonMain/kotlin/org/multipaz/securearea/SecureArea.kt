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
package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature

/**
 * An interface to a Secure Area.
 *
 * This interface exists to abstract the underlying secure area used
 * used for creation of key material and other security objects related
 * to identity documents and associated credentials.
 *
 * A Secure Area may require authentication before a key can be used and
 * this is modeled through the [KeyLockedException] and [KeyUnlockData]
 * types. By default, [KeyUnlockInteractive] is used which handles user
 * authentication out-of-band so the application will not have to worry
 * about this except to ensure that their user interface can handle platform
 * or local UI being shown to the user for authentication.
 *
 * Existing keys in a Secure Area may be invalidated and this can happen
 * on Android if e.g. the LSKF is removed or if a Cloud-based Secure Area
 * is turned down. This is modeled through the [KeyInvalidatedException]
 * being thrown when attempting to use the key. Applications can also use
 * [getKeyInvalidated] to learn ahead of time if a key is still usable.
 */
interface SecureArea {
    /**
     * A stable identifier for the Secure Area.
     *
     * This is typically just the class name but for secure areas allowing
     * multiple instances, this is of the form className@identifier.
     */
    val identifier: String

    /**
     * The name of the Secure Area, suitable for displaying to the end user.
     */
    val displayName: String

    /**
     * The list of algorithms the Secure Area supports.
     *
     * The algorithms in this list are fully specified.
     */
    val supportedAlgorithms: List<Algorithm>

    /**
     * Creates a new key.
     *
     * This creates an Elliptic Curve key-pair where the private part of the key
     * is never exposed to the user of this interface.
     *
     * The public part of the key is available in [KeyInfo.publicKey] and specific
     * implementations may expose attestations or other proof that the private part of
     * the key never leaves secure hardware.
     *
     * If an existing key with the given alias already exists it will be
     * replaced by the new key.
     *
     * @param alias A unique string to identify the newly created key. When null is passed,
     *     a new unique alias is generated automatically
     * @param createKeySettings A [CreateKeySettings] object.
     * @throws IllegalArgumentException if the underlying Secure Area Implementation
     * does not support the requested creation settings, for example the EC curve to use.
     * @return a [KeyInfo] with information about the key.
     */
    suspend fun createKey(alias: String?, createKeySettings: CreateKeySettings): KeyInfo

    /**
     * Creates a batch of new keys.
     *
     * An [OpenID4VCI Key Attestation](https://openid.github.io/OpenID4VCI/openid-4-verifiable-credential-issuance-wg-draft.html#name-key-attestation-in-jwt-form)
     * over all the keys may be returned. By default this doesn't happen but
     * can be requested by specific implementations through specializations of the [CreateKeySettings] class.
     *
     * The default implementation just calls [createKey] in sequence without generating an OpenID4VCI attestation.
     *
     * @param numKeys number of keys to create.
     * @param createKeySettings the settings for the keys to create.
     */
    suspend fun batchCreateKey(numKeys: Int, createKeySettings: CreateKeySettings): BatchCreateKeyResult {
        val keyInfos = mutableListOf<KeyInfo>()
        for (n in 1..numKeys) {
            keyInfos.add(createKey(null, createKeySettings))
        }
        return BatchCreateKeyResult(
            keyInfos = keyInfos,
            openid4vciKeyAttestationJws = null
        )
    }

    /**
     * Deletes a previously created key.
     *
     * If the key to delete doesn't exist, this is a no-op.
     *
     * @param alias The alias of the EC key to delete.
     */
    suspend fun deleteKey(alias: String)

    /**
     * Signs data with a key.
     *
     * If the key needs unlocking before use (for example user authentication
     * in any shape or form) and `keyUnlockData` isn't set or doesn't contain
     * what's needed, [KeyLockedException] is thrown. Signature algorithm must be specified at key
     * creation time using [CreateKeySettings.signingAlgorithm].
     *
     * @param alias The alias of the EC key to sign with.
     * @param dataToSign the data to sign.
     * @param keyUnlockData data used to unlock the key, `null`, or a [KeyUnlockInteractive] to
     *     handle user authentication automatically.
     * @return the signature.
     * @throws IllegalArgumentException if there is no key with the given alias
     * or the key wasn't created with purpose [KeyPurpose.SIGN].
     * @throws IllegalArgumentException if the signature algorithm isn’t compatible with the key.
     * @throws KeyLockedException if the key needs unlocking.
     * @throws KeyInvalidatedException if the key is no longer usable.
     */
    suspend fun sign(
        alias: String,
        dataToSign: ByteArray,
        unlockReason: UnlockReason = UnlockReason.Unspecified
    ): EcSignature

    /**
     * Performs Key Agreement.
     *
     * If the key needs unlocking before use (for example user authentication
     * in any shape or form) and `keyUnlockData` isn't set or doesn't contain
     * what's needed, [KeyLockedException] is thrown.
     *
     * @param alias the alias of the EC key to use.
     * @param otherKey The public EC key from the other party
     * @param keyUnlockData data used to unlock the key, `null`, or a [KeyUnlockInteractive] to
     *     handle user authentication automatically.
     * @return The shared secret.
     * @throws IllegalArgumentException if the other key isn't the same curve.
     * @throws IllegalArgumentException if there is no key with the given alias
     * or the key wasn't created with purpose [KeyPurpose.AGREE_KEY].
     * @throws KeyLockedException if the key needs unlocking.
     * @throws KeyInvalidatedException if the key is no longer usable.
     */
    suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: UnlockReason = UnlockReason.Unspecified
    ): ByteArray

    /**
     * Gets information about a key.
     *
     * This works even on keys that are invalidated.
     *
     * @param alias the alias of the EC key to use.
     * @return a [KeyInfo] object.
     * @throws IllegalArgumentException if there is no key with the given alias.
     */
    suspend fun getKeyInfo(alias: String): KeyInfo

    /**
     * Checks whether the key has been invalidated.
     *
     * @param alias the alias of the EC key to check for.
     * @return `true` if the key has been invalidated, `false` otherwise.
     * @throws IllegalArgumentException if there is no key with the given alias.
     */
    suspend fun getKeyInvalidated(alias: String): Boolean
}