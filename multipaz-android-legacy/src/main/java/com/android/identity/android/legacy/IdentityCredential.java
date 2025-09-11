/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.identity.android.legacy;

import android.icu.util.Calendar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricPrompt;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Class used to read data from a previously provisioned credential.
 *
 * Use {@link IdentityCredentialStore#getCredentialByName(String, int)} to get a
 * {@link IdentityCredential} instance.
 */
public abstract class IdentityCredential {
    /**
     * @hidden
     */
    protected IdentityCredential() {}

    /**
     * Create an ephemeral key pair to use to establish a secure channel with a reader.
     *
     * <p>Most applications will use only the public key, and only to send it to the reader,
     * allowing the private key to be used internally for {@link #encryptMessageToReader(byte[])}
     * and {@link #decryptMessageFromReader(byte[])}. The private key is also provided for
     * applications that wish to use a cipher suite that is not supported by
     * {@link IdentityCredentialStore}.
     *
     * @return ephemeral key pair to use to establish a secure channel with a reader.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public @NonNull abstract KeyPair createEphemeralKeyPair();

    /**
     * Set the ephemeral public key provided by the reader. This must be called before
     * {@link #encryptMessageToReader} or {@link #decryptMessageFromReader} can be called.
     *
     * @param readerEphemeralPublicKey The ephemeral public key provided by the reader to
     *                                 establish a secure session.
     * @throws InvalidKeyException if the given key is invalid.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public abstract void setReaderEphemeralPublicKey(@NonNull PublicKey readerEphemeralPublicKey)
            throws InvalidKeyException;

    /**
     * Set the session transcript. This must be called before
     * {@link #encryptMessageToReader} or {@link #decryptMessageFromReader} can be called.
     *
     * <p>This method can only be called once per {@link IdentityCredential} instance.
     *
     * @param sessionTranscript the session transcript.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public abstract void setSessionTranscript(@NonNull byte[] sessionTranscript);

    /**
     * Encrypt a message for transmission to the reader.
     *
     * <p>In order for this to work, {@link #setSessionTranscript(byte[])} and
     * {@link #setReaderEphemeralPublicKey(PublicKey)} must have already been
     * called.
     *
     * @param messagePlaintext unencrypted message to encrypt.
     * @return encrypted message.
     * @deprecated Applications should use {@link PresentationSession} and
     *             implement encryption/decryption themselves.
     */
    @Deprecated
    public @NonNull abstract byte[] encryptMessageToReader(@NonNull byte[] messagePlaintext);

    /**
     * Decrypt a message received from the reader.
     *
     * <p>In order for this to work, {@link #setSessionTranscript(byte[])} and
     * {@link #setReaderEphemeralPublicKey(PublicKey)} must have already been
     * called.
     *
     * @param messageCiphertext encrypted message to decrypt.
     * @return decrypted message.
     * @throws MessageDecryptionException if the ciphertext couldn't be decrypted.
     * @deprecated Applications should use {@link PresentationSession} and
     *             implement encryption/decryption themselves.
     */
    @Deprecated
    public @NonNull abstract byte[] decryptMessageFromReader(@NonNull byte[] messageCiphertext)
            throws MessageDecryptionException;

    /**
     * Gets the X.509 certificate chain for the CredentialKey which identifies this
     * credential to the issuing authority. This is the same certificate chain that
     * was returned by {@link WritableIdentityCredential#getCredentialKeyCertificateChain(byte[])}
     * when the credential was first created and its Android Keystore extension will
     * contain the <code>challenge</code> data set at that time. See the documentation
     * for that method for important information about this certificate chain.
     *
     * @return the certificate chain for this credential's CredentialKey.
     */
    public @NonNull abstract Collection<X509Certificate> getCredentialKeyCertificateChain();

    /**
     * Sets whether to allow using an authentication key which use count has been exceeded if no
     * other key is available. This must be called prior to calling
     * {@link #getEntries(byte[], Map, byte[])} or using a {@link BiometricPrompt.CryptoObject}
     * which references this object.
     *
     * <p>By default this is set to true.</p>
     *
     * @param allowUsingExhaustedKeys whether to allow using an authentication key which use count
     *                                has been exceeded if no other key is available.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public abstract void setAllowUsingExhaustedKeys(boolean allowUsingExhaustedKeys);

    /**
     * Sets whether to allow using an authentication key which has been expired if no
     * other key is available. This must be called prior to calling
     * {@link #getEntries(byte[], Map, byte[])} or using a {@link BiometricPrompt.CryptoObject}
     * which references this object.
     *
     * <p>By default this is set to false.
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202201}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param allowUsingExpiredKeys whether to allow using an authentication key which use count
     *                              has been exceeded if no other key is available.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public void setAllowUsingExpiredKeys(boolean allowUsingExpiredKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets whether the usage count of an authentication key should be increased. This must be
     * called prior to calling
     * {@link #getEntries(byte[], Map, byte[])} or using a {@link BiometricPrompt.CryptoObject}
     * which references this object.
     *
     * <p>By default this is set to true.
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202201}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param incrementKeyUsageCount whether the usage count of the key should be increased.
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public void setIncrementKeyUsageCount(boolean incrementKeyUsageCount) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets a {@link BiometricPrompt.CryptoObject} which can be used with this
     * {@link IdentityCredential}.
     *
     * <p>If {@link IdentityCredential} is hardware-backed, the returned
     * {@link BiometricPrompt.CryptoObject} is associated
     * {@link android.security.identity.IdentityCredential} object from the Android Framework.
     * If it's not hardware-backed it's not defined which kind of object it's associated with.
     * Because of this, this method is the preferred way
     * to obtain a {@link BiometricPrompt.CryptoObject} rather than to construct it
     * manually.
     *
     * <p>If the credential has no access control profiles with user-authentication and timeout 0,
     * the value {@code null} may be returned. This just means the application won't have a
     * {@link BiometricPrompt.CryptoObject} to pass to {@link BiometricPrompt} and will have to
     * pass {@code null} instead.
     *
     * @return A {@link BiometricPrompt.CryptoObject} which can be used with
     * {@link BiometricPrompt} or {@code null}.
     */
    @Nullable
    public abstract BiometricPrompt.CryptoObject getCryptoObject();

    /**
     * Retrieve data entries and associated data from this {@code IdentityCredential}.
     *
     * <p>If an access control check fails for one of the requested entries or if the entry
     * doesn't exist, the entry is simply not returned. The application can detect this
     * by using the {@link ResultData#getStatus(String, String)} method on each of the requested
     * entries.
     *
     * <p>It is permissible to call this method multiple times using the same instance. If this is
     * done the same auth-key will be used.
     *
     * <p>The application should not make any assumptions on whether user authentication is needed.
     * Instead, the application should request the data elements values first and then examine
     * the returned {@link ResultData}. If {@link ResultData#STATUS_USER_AUTHENTICATION_FAILED}
     * is returned the application should call {@link #getCryptoObject()} and use the returned
     * {@link androidx.biometric.BiometricPrompt.CryptoObject} with a
     * {@link androidx.biometric.BiometricPrompt}. Upon successful authentication the application
     * may call {@link #getEntries(byte[], Map, byte[])} again.
     *
     * <p>If not {@code null} the {@code requestMessage} parameter must contain data for the request
     * from the verifier. The content can be defined in the way appropriate for the credential, but
     * there are three requirements that must be met to work with this API:
     * <ul>
     * <li>The content must be a CBOR-encoded structure.</li>
     * <li>The CBOR structure must be a map.</li>
     * <li>The map must contain a tstr key "nameSpaces" whose value contains a map, as described in
     *     the example below.</li>
     * </ul>
     *
     * <p>If these requirements are not met the {@link InvalidRequestMessageException} exception
     * is thrown.
     *
     * <p>Here's an example of CBOR which conforms to this requirement:
     * <pre>
     *   ItemsRequest = {
     *     ? "docType" : DocType,
     *     "nameSpaces" : NameSpaces,
     *     ? "RequestInfo" : {* tstr =&gt; any} ; Additional info the reader wants to provide
     *   }
     *
     *   DocType = tstr
     *
     *   NameSpaces = {
     *     + NameSpace =&gt; DataElements    ; Requested data elements for each NameSpace
     *   }
     *
     *   NameSpace = tstr
     *
     *   DataElements = {
     *     + DataElement =&gt; IntentToRetain
     *   }
     *
     *   DataElement = tstr
     *   IntentToRetain = bool
     * </pre>
     *
     * <p>If mdoc session encryption is used (e.g. if {@link #createEphemeralKeyPair()} has been
     * called) and if {@link #setSessionTranscript(byte[])} was called, the X and Y
     * coordinates of the public part of the key-pair previously generated by
     * {@link #createEphemeralKeyPair()} must appear somewhere in the bytes of the CBOR. Each of
     * these coordinates must appear encoded with the most significant bits first and use the
     * exact amount of bits indicated by the key size of the ephemeral keys. For example, if the
     * ephemeral key is using the P-256 curve then the 32 bytes for the X coordinate encoded with
     * the most significant bits first must appear somewhere in the CBOR and ditto for the 32
     * bytes for the Y coordinate.
     *
     * <p>If {@code readerAuth} is not {@code null} it must be the bytes of a {@code COSE_Sign1}
     * structure as defined in RFC 8152. For the payload nil shall be used and the
     * detached payload is the ReaderAuthenticationBytes CBOR described below.
     * <pre>
     *     ReaderAuthentication = [
     *       "ReaderAuthentication",
     *       SessionTranscript,
     *       ItemsRequestBytes
     *     ]
     *
     *     ItemsRequestBytes = #6.24(bstr .cbor ItemsRequest)
     *
     *     ReaderAuthenticationBytes = #6.24(bstr .cbor ReaderAuthentication)
     * </pre>
     *
     * <p>where {@code ItemsRequestBytes} are the bytes in the {@code requestMessage} parameter.
     *
     * <p>The public key corresponding to the key used to make the signature, can be found in the
     * {@code x5chain} unprotected header element of the {@code COSE_Sign1} structure (as as
     * described in
     * <a href="https://tools.ietf.org/html/draft-ietf-cose-x509-04">draft-ietf-cose-x509-04</a>).
     * There will be at least one certificate in said element and there may be more (and if so,
     * each certificate must be signed by its successor).
     *
     * <p>Data elements protected by reader authentication are returned if, and only if, they are
     * mentioned in {@code requestMessage}, {@code requestMessage} is signed by the top-most
     * certificate in the reader's certificate chain, and the data element is configured
     * with an {@link AccessControlProfile} configured with an X.509 certificate which appears
     * in the certificate chain.
     *
     * <p>Note that only items referenced in {@code entriesToRequest} are returned - the
     * {@code requestMessage} parameter is used only for enforcing reader authentication.
     *
     * <p>The reason for having {@code requestMessage} and {@code entriesToRequest} as separate
     * parameters is that the former represents a request from the remote verifier device
     * (optionally signed) and this allows the application to filter the request to not include
     * data elements which the user has not consented to sharing.
     *
     * @param requestMessage         If not {@code null}, must contain CBOR data conforming to
     *                               the schema mentioned above.
     * @param entriesToRequest       The entries to request, organized as a map of namespace
     *                               names with each value being a collection of data elements
     *                               in the given namespace.
     * @param readerSignature        A {@code COSE_Sign1} structure as described above or
     *                               {@code null} if reader authentication is not being used.
     * @return A {@link ResultData} object containing entry data organized by namespace and a
     *         cryptographically authenticated representation of the same data.
     * @throws NoAuthenticationKeyAvailableException  is thrown if authentication keys were never
     *                                                provisioned, none are available, or the
     *                                                available ones are all exhausted and
     *                                                {@link #setAllowUsingExhaustedKeys(boolean)}
     *                                                was called with {@code false}.
     * @throws InvalidReaderSignatureException        if the reader signature is invalid, or it
     *                                                doesn't contain a certificate chain, or if
     *                                                the signature failed to validate.
     * @throws InvalidRequestMessageException         if the requestMessage is malformed.
     * @throws EphemeralPublicKeyNotFoundException    if the ephemeral public key was not found in
     *                                                the session transcript.
     * @throws CredentialInvalidatedException         if the credential has been invalidated
     * @deprecated Use {@link PresentationSession} instead.
     */
    @Deprecated
    public abstract @NonNull ResultData getEntries(
            @Nullable byte[] requestMessage,
            @NonNull Map<String, Collection<String>> entriesToRequest,
            @Nullable byte[] readerSignature)
            throws NoAuthenticationKeyAvailableException,
            InvalidReaderSignatureException, EphemeralPublicKeyNotFoundException,
            InvalidRequestMessageException;

    /**
     * Sets the number of dynamic authentication keys the {@code IdentityCredential} will maintain,
     * and the number of times each should be used.
     *
     * <p>{@code IdentityCredential}s will select the least-used dynamic authentication key each
     * time {@link #getEntries(byte[], Map, byte[])} is called. {@code IdentityCredential}s
     * for which this method has not been called behave as though it had been called wit
     * {@code keyCount} 0 and {@code maxUsesPerKey} 1.
     *
     * <p>The effect of this method is like calling
     * {@link #setAvailableAuthenticationKeys(int, int, long)} with the last parameter is set to 0.
     *
     * @param keyCount      The number of active, certified dynamic authentication keys the
     *                      {@code IdentityCredential} will try to keep available. This value
     *                      must be non-negative.
     * @param maxUsesPerKey The maximum number of times each of the keys will be used before it's
     *                      eligible for replacement. This value must be greater than zero.
     * @deprecated Use {@link #setAvailableAuthenticationKeys(int, int, long)} instead.
     */
    @Deprecated
    public abstract void setAvailableAuthenticationKeys(int keyCount, int maxUsesPerKey);

    /**
     * Gets a collection of dynamic authentication keys that need certification.
     *
     * <p>When there aren't enough certified dynamic authentication keys (either because the key
     * count has been increased, one or more keys have reached their usage count, or keys have
     * expired), this method will generate replacement keys and certificates and return them for
     * issuer certification. The issuer certificates and associated static authentication data
     * must then be provided back to the {@code IdentityCredential} using
     * {@link #storeStaticAuthenticationData(X509Certificate, Calendar, byte[])}.
     *
     * <p>Each X.509 certificate is signed by CredentialKey. The certificate chain for CredentialKey
     * can be obtained using the {@link #getCredentialKeyCertificateChain()} method.
     *
     * <p>The following non-optional fields for the X.509 certificate are set as follows:
     * <ul>
     *  <li>version: INTEGER 2 (means v3 certificate).</li>
     *  <li>serialNumber: INTEGER 1 (fixed value: same on all certs).</li>
     *  <li>signature: must be set to ECDSA.</li>
     *  <li>subject: CN shall be set to "Android Identity Credential Authentication Key" (fixed
     *  value: same on all certs).</li>
     *  <li>issuer: CN shall be set to "Android Identity Credential Key" (fixed value: same on
     *  all certs).</li>
     *  <li>validity: should be from current time and one year in the future (365 days).</li>
     *  <li>subjectPublicKeyInfo: must contain attested public key.</li>
     * </ul>
     *
     * <p>If running on a store with {@link IdentityCredentialStore#FEATURE_VERSION_202101} or
     * later, each X.509 certificate contains an X.509 extension at OID 1.3.6.1.4.1.11129.2.1.26
     * which contains a DER encoded OCTET STRING with the bytes of the CBOR with the following
     * CDDL:
     * <pre>
     *   ProofOfBinding = [
     *     "ProofOfBinding",
     *     bstr,              // Contains SHA-256(ProofOfProvisioning)
     *   ]
     * </pre>
     * <p>This CBOR enables an issuer to determine the exact state of the credential it
     * returns issuer-signed data for.
     *
     * @return A collection of X.509 certificates for dynamic authentication keys that need issuer
     * certification.
     */
    public @NonNull abstract Collection<X509Certificate> getAuthKeysNeedingCertification();

    /**
     * Gets a collection of dynamic authentication keys that need certification.
     *
     * <p>When there aren't enough certified dynamic authentication keys (either because the key
     * count has been increased, one or more keys have reached their usage count, or keys have
     * expired), this method will generate replacement keys and certificates and return them for
     * issuer certification. The issuer certificates and associated static authentication data
     * must then be provided back to the {@code IdentityCredential} using
     * {@link #storeStaticAuthenticationData(X509Certificate, Calendar, byte[])}.
     *
     * <p>Each X.509 certificate chain is signed by ultimately signed by Keystore Root and the
     * keystore extensions can be found on the leaf certificate.
     *
     * @param challenge a byte array whose contents should be unique, fresh and provided by
     * the issuing authority. The value provided is embedded in the keystore attestation extension
     * and enables the issuing authority to verify that the returned attestation is for this
     * transaction. If Optional.empty() is provided the challenge value will not be set.
     *
     * @return A collection of X.509 certificates chains for dynamic authentication keys that need
     * issuer certification.
     */
    public @NonNull abstract List<List<X509Certificate>> getAuthKeyChainsNeedingCertification(
        byte[] challenge);

    /**
     * Store authentication data associated with a dynamic authentication key.
     *
     * <p>This should only be called for an authenticated key returned by
     * {@link #getAuthKeysNeedingCertification()}.</p>
     *
     * @param authenticationKey The dynamic authentication key for which certification and
     *                          associated static authentication data is being provided.
     * @param staticAuthData    Static authentication data provided by the issuer that validates
     *                          the authenticity
     *                          and integrity of the credential data fields.
     * @throws UnknownAuthenticationKeyException If the given authentication key is not recognized.
     * @deprecated Use {@link #storeStaticAuthenticationData(X509Certificate, Calendar, byte[])}
     *     instead.
     */
    @Deprecated
    public abstract void storeStaticAuthenticationData(
        @NonNull X509Certificate authenticationKey,
        @NonNull byte[] staticAuthData)
        throws UnknownAuthenticationKeyException;

    /**
     * Store authentication data associated with a dynamic authentication key.
     *
     * <p>This should only be called for an authenticated key returned by
     * {@link #getAuthKeysNeedingCertification()}.</p>
     *
     * @param authenticationKey The dynamic authentication key for which certification and
     *                          associated static authentication data is being provided.
     * @param staticAuthData    Static authentication data provided by the issuer that validates
     *                          the authenticity
     *                          and integrity of the credential data fields.
     * @throws UnknownAuthenticationKeyException If the given authentication key is not recognized.
     * @deprecated Use {@link #storeStaticAuthenticationData(X509Certificate, Calendar, byte[])}
     *     instead.
     */
    @Deprecated
    public abstract void storeStaticAuthenticationData(
        @NonNull PublicKey authenticationKey,
        @NonNull byte[] staticAuthData)
        throws UnknownAuthenticationKeyException;

    /**
     * Store authentication data associated with a dynamic authentication key.
     *
     * <p>This should only be called for an authenticated key returned by
     * {@link #getAuthKeysNeedingCertification()}.</p>
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202101}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param authenticationKey The dynamic authentication key for which certification and
     *                          associated static authentication data is being provided.
     * @param expirationDate    The expiration date of the static authentication data.
     * @param staticAuthData    Static authentication data provided by the issuer that validates
     *                          the authenticity
     *                          and integrity of the credential data fields.
     * @throws UnknownAuthenticationKeyException If the given authentication key is not recognized.
     */
    @Deprecated
    public void storeStaticAuthenticationData(
            @NonNull X509Certificate authenticationKey,
            @NonNull Calendar expirationDate,
            @NonNull byte[] staticAuthData)
            throws UnknownAuthenticationKeyException {
        throw new UnsupportedOperationException();
    }

    /**
     * Store authentication data associated with a dynamic authentication key.
     *
     * <p>This should only be called for an authenticated key returned by
     * {@link #getAuthKeysNeedingCertification()}.</p>
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202101}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * <p>This method will find search for the matching keystore key for the given public key and
     * store the static authentication data with it.
     *
     * @param authenticationKey The dynamic authentication key for which certification and
     *                          associated static authentication data is being provided.
     * @param expirationDate    The expiration date of the static authentication data.
     * @param staticAuthData    Static authentication data provided by the issuer that validates
     *                          the authenticity
     *                          and integrity of the credential data fields.
     * @throws UnknownAuthenticationKeyException If the given authentication key is not recognized.
     */
    public void storeStaticAuthenticationData(
        @NonNull PublicKey authenticationKey,
        @NonNull Calendar expirationDate,
        @NonNull byte[] staticAuthData)
        throws UnknownAuthenticationKeyException {
        throw new UnsupportedOperationException();
    }


    /**
     * Get the number of times the dynamic authentication keys have been used.
     *
     * @return int array of dynamic authentication key usage counts.
     */
    public @NonNull abstract int[] getAuthenticationDataUsageCount();


    /**
     * Proves ownership of a credential.
     *
     * <p>This method returns a COSE_Sign1 data structure signed by the CredentialKey
     * with payload set to {@code ProofOfOwnership} as defined below.</p>
     * <pre>
     *     ProofOfOwnership = [
     *          "ProofOfOwnership",           ; tstr
     *          tstr,                         ; DocType
     *          bstr,                         ; Challenge
     *          bool                          ; true if this is a test credential, should
     *                                        ; always be false.
     *      ]
     * </pre>
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202101}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param challenge is a non-empty byte array whose contents should be unique, fresh and
     *                  provided by the issuing authority. The value provided is embedded in the
     *                  generated CBOR and enables the issuing authority to verify that the
     *                  returned proof is fresh.
     * @return the COSE_Sign1 data structure above
     */
    public @NonNull byte[] proveOwnership(@NonNull byte[] challenge)  {
        throw new UnsupportedOperationException();
    }

    /**
     * Deletes a credential.
     *
     * <p>This method returns a COSE_Sign1 data structure signed by the CredentialKey
     * with payload set to {@code ProofOfDeletion} as defined below.</p>
     * <pre>
     *     ProofOfDeletion = [
     *          "ProofOfDeletion",            ; tstr
     *          tstr,                         ; DocType
     *          bstr,                         ; Challenge
     *          bool                          ; true if this is a test credential, should
     *                                        ; always be false.
     *      ]
     * </pre>
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202101}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param challenge is a non-empty byte array whose contents should be unique, fresh and
     *                  provided by the issuing authority. The value provided is embedded in the
     *                  generated CBOR and enables the issuing authority to verify that the
     *                  returned proof is fresh.
     * @return the COSE_Sign1 data structure above
     */
    public @NonNull byte[] delete(@NonNull byte[] challenge)  {
        throw new UnsupportedOperationException();
    }

    /**
     * Updates the credential with new access control profiles and data items.
     *
     * <p>This method is similar to
     * {@link WritableIdentityCredential#personalize(PersonalizationData)} except that it operates
     * on an existing credential, see the documentation for that method for the format of the
     * returned data.
     *
     * <p>If this call succeeds an side-effect is that all dynamic authentication keys for the
     * credential are deleted. The application will need to use
     * {@link #getAuthKeysNeedingCertification()} to generate replacement keys and return
     * them for issuer certification.
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202101}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param personalizationData   The data to update, including access control profiles
     *                              and data elements and their values, grouped into namespaces.
     * @return A COSE_Sign1 data structure, see above.
     */
    public @NonNull byte[] update(@NonNull PersonalizationData personalizationData) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the number of dynamic authentication keys the {@code IdentityCredential} will maintain,
     * the number of times each should be used, and the minimum amount of time it's valid for.
     *
     * <p>The Identity Credential system will select the least-used dynamic authentication key each
     * time {@link #getEntries(byte[], Map, byte[])} is called. Identity Credentials
     * for which this method has not been called behave as though it had been called wit
     * {@code keyCount} 0, {@code maxUsesPerKey} 1, and {@code minValidTimeMillis} 0.
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202301}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @param keyCount      The number of active, certified dynamic authentication keys the
     *                      {@code IdentityCredential} will try to keep available. This value
     *                      must be non-negative.
     * @param maxUsesPerKey The maximum number of times each of the keys will be used before it's
     *                      eligible for replacement. This value must be greater than zero.
     * @param minValidTimeMillis If a key has less time left than this value it will be eliglible
     *                           for replacement.
     */
    public void
    setAvailableAuthenticationKeys(int keyCount, int maxUsesPerKey, long minValidTimeMillis) {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the expiration times of dynamic authentication keys.
     *
     * <p>The returned array may have <code>null</code> values if the key hasn't been
     * certified or if it was certified without an expiration date.
     *
     * <p>This is only implemented on {@link IdentityCredentialStore#FEATURE_VERSION_202301}, fails
     * with {@link UnsupportedOperationException} if using a store with a lesser version.
     *
     * @return list of authentication key expirations.
     */
    public @NonNull
    List<Calendar> getAuthenticationDataExpirations() {
        throw new UnsupportedOperationException();
    }

    /**
     * Looks up the next static authentication key to use. This may be null if no valid key is
     * available.
     *
     * @return the next static authentication key to use.
     */
    @Nullable
    public byte[] peekNextAuthenticationKey() {
        throw new UnsupportedOperationException();
    }
}
