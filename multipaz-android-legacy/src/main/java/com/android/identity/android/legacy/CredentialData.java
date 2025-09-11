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

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.icu.util.Calendar;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.AtomicFile;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.multipaz.util.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.builder.ArrayBuilder;
import co.nstant.in.cbor.builder.MapBuilder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Number;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

/**
 * Internal routines used to manage credential data.
 */
class CredentialData {
    private static final String TAG = "CredentialData";

    private final Context mContext;
    private final String mCredentialName;
    private final File mStorageDirectory;

    private String mDocType = "";
    private String mCredentialKeyAlias = "";
    private Collection<X509Certificate> mCertificateChain = null;
    private byte[] mProofOfProvisioningSha256 = null;
    private AbstractList<AccessControlProfile> mAccessControlProfiles = new ArrayList<>();
    private AbstractMap<Integer, AccessControlProfile> mProfileIdToAcpMap = new HashMap<>();
    private AbstractList<PersonalizationData.NamespaceData> mNamespaceDatas = new ArrayList<>();

    private int mAuthKeyCount = 0;
    private int mAuthMaxUsesPerKey = 1;
    private long mAuthKeyMinValidTimeMillis = 0;

    // The alias for the key that must be unlocked by user auth for every reader session.
    //
    // Is non-empty if and only if there is at least one ACP requiring user-auth.
    private String mPerReaderSessionKeyAlias = "";

    // A map from ACP id to key alias.
    //
    // This is for ACPs with positive timeouts.
    private AbstractMap<Integer, String> mAcpTimeoutKeyAliases;

    // The data for each authentication key, this is always mAuthKeyCount items.
    private AbstractList<AuthKeyData> mAuthKeyDatas = new ArrayList<>();

    private CredentialData(@NonNull Context context, @NonNull File storageDirectory,
                           @NonNull String credentialName) {
        mContext = context;
        mStorageDirectory = storageDirectory;
        mCredentialName = credentialName;
    }

    static DataItem namespaceDataToCbor(PersonalizationData.NamespaceData entryNamespace) {
        CborBuilder entryBuilder = new CborBuilder();
        ArrayBuilder<CborBuilder> entryArrayBuilder = entryBuilder.addArray();
        for (String entryName : entryNamespace.getEntryNames()) {
            byte[] entryValue = entryNamespace.getEntryValue(entryName);
            Collection<AccessControlProfileId> accessControlProfileIds =
                    entryNamespace.getAccessControlProfileIds(
                            entryName);

            CborBuilder accessControlProfileIdsBuilder = new CborBuilder();
            ArrayBuilder<CborBuilder> accessControlProfileIdsArrayBuilder =
                    accessControlProfileIdsBuilder.addArray();
            for (AccessControlProfileId id : accessControlProfileIds) {
                accessControlProfileIdsArrayBuilder.add(id.getId());
            }

            MapBuilder<ArrayBuilder<CborBuilder>> entryMapBuilder = entryArrayBuilder.addMap();
            entryMapBuilder.put("name", entryName);
            entryMapBuilder.put(new UnicodeString("value"), Util.cborDecode(entryValue));
            entryMapBuilder.put(new UnicodeString("accessControlProfiles"),
                    accessControlProfileIdsBuilder.build().get(0));
        }
        return entryBuilder.build().get(0);
    }

    public static PersonalizationData.NamespaceData namespaceDataFromCbor(String namespaceName,
            DataItem dataItem) {
        if (!(dataItem instanceof Array)) {
            throw new IllegalArgumentException("Item is not an Array");
        }
        Array array = (Array) dataItem;

        PersonalizationData.Builder builder = new PersonalizationData.Builder();

        for (DataItem item : array.getDataItems()) {
            if (!(item instanceof co.nstant.in.cbor.model.Map)) {
                throw new IllegalArgumentException("Item is not a map");
            }
            co.nstant.in.cbor.model.Map map = (co.nstant.in.cbor.model.Map) item;

            String name = ((UnicodeString) map.get(new UnicodeString("name"))).getString();

            List<AccessControlProfileId> accessControlProfileIds = new ArrayList<>();
            Array accessControlProfileArray =
                    (Array) map.get(
                            new UnicodeString("accessControlProfiles"));
            for (DataItem acpIdItem : accessControlProfileArray.getDataItems()) {
                accessControlProfileIds.add(
                        new AccessControlProfileId(((Number) acpIdItem).getValue().intValue()));
            }

            DataItem cborValue = map.get(new UnicodeString("value"));
            byte[] data = Util.cborEncode(cborValue);
            builder.putEntry(namespaceName, name, accessControlProfileIds, data);
        }

        return builder.build().getNamespaceData(namespaceName);
    }

    public static AccessControlProfile accessControlProfileFromCbor(DataItem item) {
        if (!(item instanceof co.nstant.in.cbor.model.Map)) {
            throw new IllegalArgumentException("Item is not a map");
        }
        co.nstant.in.cbor.model.Map map = (co.nstant.in.cbor.model.Map) item;

        int accessControlProfileId = ((Number) map.get(
                new UnicodeString("id"))).getValue().intValue();
        AccessControlProfile.Builder builder = new AccessControlProfile.Builder(
                new AccessControlProfileId(accessControlProfileId));

        item = map.get(new UnicodeString("readerCertificate"));
        if (item != null) {
            byte[] rcBytes = ((ByteString) item).getBytes();
            CertificateFactory certFactory = null;
            try {
                certFactory = CertificateFactory.getInstance("X.509");
                builder.setReaderCertificate((X509Certificate) certFactory.generateCertificate(
                        new ByteArrayInputStream(rcBytes)));
            } catch (CertificateException e) {
                throw new IllegalArgumentException("Error decoding readerCertificate", e);
            }
        }

        builder.setUserAuthenticationRequired(false);
        item = map.get(new UnicodeString("capabilityType"));
        if (item != null) {
            // TODO: deal with -1 as per entryNamespaceToCbor()
            builder.setUserAuthenticationRequired(true);
            item = map.get(new UnicodeString("timeout"));
            builder.setUserAuthenticationTimeout(
                    item == null ? 0 : ((Number) item).getValue().intValue());
        }
        return builder.build();
    }

    static DataItem accessControlProfileToCbor(AccessControlProfile accessControlProfile) {
        CborBuilder cborBuilder = new CborBuilder();
        MapBuilder<CborBuilder> mapBuilder = cborBuilder.addMap();

        mapBuilder.put("id", accessControlProfile.getAccessControlProfileId().getId());
        X509Certificate readerCertificate = accessControlProfile.getReaderCertificate();
        if (readerCertificate != null) {
            try {
                mapBuilder.put("readerCertificate", readerCertificate.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new IllegalStateException("Error encoding reader mCertificate", e);
            }
        }
        if (accessControlProfile.isUserAuthenticationRequired()) {
            mapBuilder.put("capabilityType", 1); // TODO: what value to put here?
            long timeout = accessControlProfile.getUserAuthenticationTimeout();
            if (timeout != 0) {
                mapBuilder.put("timeout", timeout);
            }
        }
        return cborBuilder.build().get(0);
    }

    static @NonNull X509Certificate generateAuthenticationKeyCert(String authKeyAlias,
            String credentialKeyAlias,
            byte[] proofOfProvisioningSha256) {
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);

            X509Certificate selfSignedCert = (X509Certificate) ks.getCertificate(authKeyAlias);
            PublicKey publicKey = selfSignedCert.getPublicKey();

            PrivateKey privateKey = ((KeyStore.PrivateKeyEntry) ks.getEntry(credentialKeyAlias,
                    null)).getPrivateKey();

            X500Name issuer = new X500Name("CN=Android Identity Credential Key");
            X500Name subject = new X500Name("CN=Android Identity Credential Authentication Key");

            Date now = new Date();
            Date expirationDate = new Date(now.getTime() + MILLISECONDS.convert(365, DAYS));
            BigInteger serial = BigInteger.ONE;
            JcaX509v3CertificateBuilder builder =
                    new JcaX509v3CertificateBuilder(issuer,
                            serial,
                            now,
                            expirationDate,
                            subject,
                            publicKey);

            if (proofOfProvisioningSha256 != null) {
                byte[] encodedProofOfBinding = Util.cborEncode(new CborBuilder()
                        .addArray()
                        .add("ProofOfBinding")
                        .add(proofOfProvisioningSha256)
                        .end()
                        .build().get(0));
                builder.addExtension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.26"), false,
                        encodedProofOfBinding);
            }

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(privateKey);
            byte[] encodedCert = builder.build(signer).getEncoded();

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream bais = new ByteArrayInputStream(encodedCert);
            X509Certificate result = (X509Certificate) cf.generateCertificate(bais);
            return result;
        } catch (IOException
                | KeyStoreException
                | NoSuchAlgorithmException
                | UnrecoverableEntryException
                | CertificateException
                | OperatorCreationException e) {
            throw new IllegalStateException("Error signing public key with private key", e);
        }
    }

    /**
     * Deletes KeyStore keys no longer needed when updating a credential (every KeyStore key
     * except for CredentialKey).
     */
    void deleteKeysForReplacement() {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
        } catch (CertificateException
                | IOException
                | NoSuchAlgorithmException
                | KeyStoreException e) {
            throw new RuntimeException("Error loading keystore", e);
        }

        // Nuke all keys except for CredentialKey.
        try {
            if (!mPerReaderSessionKeyAlias.isEmpty()) {
                ks.deleteEntry(mPerReaderSessionKeyAlias);
            }
            for (String alias : mAcpTimeoutKeyAliases.values()) {
                ks.deleteEntry(alias);
            }
            for (AuthKeyData authKeyData : mAuthKeyDatas) {
                if (!authKeyData.mAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mAlias);
                }
                if (!authKeyData.mPendingAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mPendingAlias);
                }
            }
        } catch (KeyStoreException e) {
            throw new RuntimeException("Error deleting key", e);
        }
    }

    private KeyStore loadKeyStore() {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
        } catch (CertificateException
                 | IOException
                 | NoSuchAlgorithmException
                 | KeyStoreException e) {
            throw new RuntimeException("Error loading keystore", e);
        }
        return ks;
    }

    @CanIgnoreReturnValue
    static CredentialData createCredentialData(@NonNull Context context,
                                               @NonNull File storageDirectory,
                                               @NonNull String docType,
                                               @NonNull String credentialName,
                                               @NonNull String credentialKeyAlias,
                                               @NonNull Collection<X509Certificate> certificateChain,
                                               @NonNull PersonalizationData personalizationData,
                                               @NonNull byte[] proofOfProvisioningSha256,
                                               boolean isReplacement) {
        if (!isReplacement) {
            if (credentialAlreadyExists(context, storageDirectory, credentialName)) {
                throw new RuntimeException("Credential with given name already exists");
            }
        }

        CredentialData data = new CredentialData(context, storageDirectory, credentialName);
        data.mDocType = docType;
        data.mCredentialKeyAlias = credentialKeyAlias;
        data.mCertificateChain = certificateChain;
        data.mProofOfProvisioningSha256 = proofOfProvisioningSha256;
        data.mAccessControlProfiles = new ArrayList<>();
        data.mProfileIdToAcpMap = new HashMap<>();
        for (AccessControlProfile item : personalizationData.getAccessControlProfiles()) {
            data.mAccessControlProfiles.add(item);
            data.mProfileIdToAcpMap.put(item.getAccessControlProfileId().getId(), item);
        }
        data.mNamespaceDatas = new ArrayList<>();
        data.mNamespaceDatas.addAll(personalizationData.getNamespaceDatas());

        data.mAcpTimeoutKeyAliases = new HashMap<>();
        for (AccessControlProfile profile : personalizationData.getAccessControlProfiles()) {
            boolean isAuthRequired = profile.isUserAuthenticationRequired();
            long timeoutMillis = profile.getUserAuthenticationTimeout();
            if (isAuthRequired) {
                if (timeoutMillis == 0) {
                    // Only create a require-auth-for-every-use keys if one of the ACPs actually
                    // require it. We do this to allow for creating credentials on devices
                    // where biometrics isn't configured or doesn't exist.
                    //
                    ensurePerReaderSessionKey(credentialName, data);
                }

                ensureAcpTimoutKeyForProfile(credentialName, data, profile, timeoutMillis);
            }
        }

        data.createDataEncryptionKey();

        data.saveToDisk();
        return data;
    }

    static boolean credentialAlreadyExists(@NonNull Context context,
                                           @NonNull File storageDirectory,
                                           @NonNull String credentialName) {
        String filename = getFilenameForCredentialData(credentialName);
        AtomicFile file = new AtomicFile(new File(storageDirectory, filename));
        try {
            file.openRead();
        } catch (FileNotFoundException e) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")  // setUserAuthenticationValidityDurationSeconds()
    private static void ensurePerReaderSessionKey(String credentialName,
            CredentialData data) {
        if (!data.mPerReaderSessionKeyAlias.isEmpty()) {
            return;
        }
        data.mPerReaderSessionKeyAlias = getAcpKeyAliasFromCredentialName(credentialName);
        try {
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    data.mPerReaderSessionKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128)
                    // Can't use setUserAuthenticationParameters() since we need to run
                    // on API level 24.
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1); // Auth for every use
            kg.init(builder.build());
            kg.generateKey();
        } catch (InvalidAlgorithmParameterException
                | NoSuchAlgorithmException
                | NoSuchProviderException e) {
            throw new RuntimeException("Error creating ACP auth-bound key", e);
        }
    }

    @SuppressWarnings("deprecation")  // setUserAuthenticationValidityDurationSeconds()
    private static void ensureAcpTimoutKeyForProfile(String credentialName, CredentialData data,
            AccessControlProfile profile, long timeoutMilliSeconds) {
        if (timeoutMilliSeconds > 0) {
            int profileId = profile.getAccessControlProfileId().getId();
            String acpAlias = getAcpTimeoutKeyAliasFromCredentialName(credentialName,
                    profileId);
            try {
                final int timeoutSeconds = (int) (timeoutMilliSeconds / 1000);
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        acpAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        // Can't use setUserAuthenticationParameters() since we need to run
                        // on API level 24.
                        .setUserAuthenticationValidityDurationSeconds(timeoutSeconds)
                        .setKeySize(128);
                kg.init(builder.build());
                kg.generateKey();
            } catch (InvalidAlgorithmParameterException
                    | NoSuchAlgorithmException
                    | NoSuchProviderException e) {
                throw new RuntimeException("Error creating ACP auth-bound timeout key", e);
            }
            data.mAcpTimeoutKeyAliases.put(profileId, acpAlias);
        }
    }

    static CredentialData loadCredentialData(@NonNull Context context,
                                             @NonNull File storageDirectory,
                                             @NonNull String credentialName) {
        CredentialData data = new CredentialData(context, storageDirectory, credentialName);
        String dataKeyAlias = getDataKeyAliasFromCredentialName(credentialName);
        if (!data.loadFromDisk(dataKeyAlias)) {
            return null;
        }
        return data;
    }

    // Keep naming in sync with KEY_FOR_AUTH_PER_PRESENTATION_ALIAS in
    // KeystorePresentationSession.java.
    //
    static String escapeCredentialName(String componentName, String credentialName) {
        try {
            return "identity_credential_" + componentName + "_"
                    + URLEncoder.encode(credentialName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unexpected UnsupportedEncodingException", e);
        }
    }

    static String getFilenameForCredentialData(String credentialName) {
        return escapeCredentialName("data", credentialName);
    }

    static String getAliasFromCredentialName(String credentialName) {
        return escapeCredentialName("credkey", credentialName);
    }

    static String getDataKeyAliasFromCredentialName(String credentialName) {
        return escapeCredentialName("datakey", credentialName);
    }

    static String getAcpTimeoutKeyAliasFromCredentialName(String credentialName, int acpProfileId) {
        return escapeCredentialName("acp_timeout_for_id" + acpProfileId, credentialName);
    }

    static String getAcpKeyAliasFromCredentialName(String credentialName) {
        return escapeCredentialName("acp", credentialName);
    }

    PrivateKey getCredentialKeyPrivate() {
        KeyStore ks;
        KeyStore.Entry entry;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            entry = ks.getEntry(mCredentialKeyAlias, null);
        } catch (CertificateException
                | IOException
                | NoSuchAlgorithmException
                | KeyStoreException
                | UnrecoverableEntryException e) {
            throw new RuntimeException("Error loading keystore", e);
        }
        return ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
    }

    // Returns COSE_Sign1 with payload set to ProofOfOwnership
    @NonNull byte[] proveOwnership(@NonNull byte[] challenge) {
        PrivateKey key = getCredentialKeyPrivate();

        CborBuilder signedDataBuilder = new CborBuilder();
        signedDataBuilder.addArray()
                .add("ProofOfOwnership")
                .add(mDocType)
                .add(challenge)
                .add(false);
        byte[] signatureBytes;
        try {
            ByteArrayOutputStream dtsBaos = new ByteArrayOutputStream();
            CborEncoder dtsEncoder = new CborEncoder(dtsBaos);
            dtsEncoder.encode(signedDataBuilder.build().get(0));
            byte[] dataToSign = dtsBaos.toByteArray();

            signatureBytes = Util.cborEncode(Util.coseSign1Sign(key,
                    "SHA256withECDSA", dataToSign,
                    null,
                    null));
        } catch (CborException e) {
            throw new RuntimeException("Error building ProofOfOwnership", e);
        }
        return signatureBytes;
    }

    // Returns COSE_Sign1 with payload set to ProofOfDeletion
    static byte[] buildProofOfDeletionSignature(String docType, PrivateKey key, byte[] challenge) {

        CborBuilder signedDataBuilder = new CborBuilder();
        ArrayBuilder<CborBuilder> arrayBuilder = signedDataBuilder.addArray();
        arrayBuilder.add("ProofOfDeletion")
                .add(docType);
        if (challenge != null) {
            arrayBuilder.add(challenge);
        }
        arrayBuilder.add(false);

        byte[] signatureBytes;
        try {
            ByteArrayOutputStream dtsBaos = new ByteArrayOutputStream();
            CborEncoder dtsEncoder = new CborEncoder(dtsBaos);
            dtsEncoder.encode(signedDataBuilder.build().get(0));
            byte[] dataToSign = dtsBaos.toByteArray();

            signatureBytes = Util.cborEncode(Util.coseSign1Sign(key,
                "SHA256withECDSA", dataToSign,
                    null,
                    null));
        } catch (CborException e) {
            throw new RuntimeException("Error building ProofOfDeletion", e);
        }
        return signatureBytes;
    }

    static byte[] delete(@NonNull Context context, @NonNull File storageDirectory,
                         @NonNull String credentialName, @NonNull byte[] challenge) {
        String filename = getFilenameForCredentialData(credentialName);
        AtomicFile file = new AtomicFile(new File(storageDirectory, filename));
        try {
            file.openRead();
        } catch (FileNotFoundException e) {
            return null;
        }

        CredentialData data = new CredentialData(context, storageDirectory, credentialName);
        String dataKeyAlias = getDataKeyAliasFromCredentialName(credentialName);
        try {
            if (!data.loadFromDisk(dataKeyAlias)) {
                Logger.INSTANCE.e(TAG, "Error loading file from disk. Deleting anyway.");
                file.delete();
                return null;
            }
        } catch (RuntimeException e) {
            Logger.INSTANCE.e(TAG, "Error parsing file on disk (old version?). Deleting anyway.");
            file.delete();
            return null;
        }

        KeyStore ks;
        KeyStore.Entry entry;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            entry = ks.getEntry(data.mCredentialKeyAlias, null);
        } catch (CertificateException
                | IOException
                | NoSuchAlgorithmException
                | KeyStoreException
                | UnrecoverableEntryException e) {
            throw new RuntimeException("Error loading keystore", e);
        }

        byte[] signature = buildProofOfDeletionSignature(data.mDocType,
                ((KeyStore.PrivateKeyEntry) entry).getPrivateKey(), challenge);

        file.delete();

        // Nuke all keys.
        try {
            ks.deleteEntry(data.mCredentialKeyAlias);
            if (!data.mPerReaderSessionKeyAlias.isEmpty()) {
                ks.deleteEntry(data.mPerReaderSessionKeyAlias);
            }
            for (String alias : data.mAcpTimeoutKeyAliases.values()) {
                ks.deleteEntry(alias);
            }
            for (AuthKeyData authKeyData : data.mAuthKeyDatas) {
                if (!authKeyData.mAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mAlias);
                }
                if (!authKeyData.mPendingAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mPendingAlias);
                }
            }
        } catch (KeyStoreException e) {
            throw new RuntimeException("Error deleting key", e);
        }

        return signature;
    }

    static boolean deleteForMigration(@NonNull Context context, @NonNull File storageDirectory,
                         @NonNull String credentialName) {
        String filename = getFilenameForCredentialData(credentialName);
        AtomicFile file = new AtomicFile(new File(storageDirectory, filename));
        try {
            file.openRead();
        } catch (FileNotFoundException e) {
            return false;
        }

        CredentialData data = new CredentialData(context, storageDirectory, credentialName);
        String dataKeyAlias = getDataKeyAliasFromCredentialName(credentialName);
        try {
            var unused = data.loadFromDisk(dataKeyAlias);
        } catch (RuntimeException e) {
            Logger.INSTANCE.e(TAG, "Error parsing file on disk (old version?). Deleting anyway.");
        }
        file.delete();

        KeyStore ks;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
        } catch (CertificateException
                 | IOException
                 | NoSuchAlgorithmException
                 | KeyStoreException e) {
            throw new RuntimeException("Error loading keystore", e);
        }

        // Nuke all keys.
        try {
            if (!data.mPerReaderSessionKeyAlias.isEmpty()) {
                ks.deleteEntry(data.mPerReaderSessionKeyAlias);
            }
            for (String alias : data.mAcpTimeoutKeyAliases.values()) {
                ks.deleteEntry(alias);
            }
            for (AuthKeyData authKeyData : data.mAuthKeyDatas) {
                if (!authKeyData.mAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mAlias);
                }
                if (!authKeyData.mPendingAlias.isEmpty()) {
                    ks.deleteEntry(authKeyData.mPendingAlias);
                }
            }
        } catch (KeyStoreException e) {
            throw new RuntimeException("Error deleting key", e);
        }

        return true;
    }

    private void createDataEncryptionKey() {
        // TODO: it could maybe be nice to encrypt data with the appropriate auth-bound
        //  key (the one associated with the ACP with the longest timeout), if it doesn't
        //  have a no-auth ACP.
        try {
            String dataKeyAlias = getDataKeyAliasFromCredentialName(mCredentialName);
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    dataKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128);
            kg.init(builder.build());
            kg.generateKey();
        } catch (InvalidAlgorithmParameterException
                | NoSuchAlgorithmException
                | NoSuchProviderException e) {
            throw new RuntimeException("Error creating data encryption key", e);
        }
    }

    private void saveToDisk() {
        CborBuilder builder = new CborBuilder();
        MapBuilder<CborBuilder> map = builder.addMap();

        saveToDiskBasic(map);
        saveToDiskAuthDatas(map);
        saveToDiskACPs(map);
        saveToDiskNamespaceDatas(map);
        saveToDiskAuthKeys(map);

        byte[] cleartextDataToSaveBytes = saveToDiskEncode(builder);

        byte[] dataToSaveBytes = saveToDiskEncrypt(cleartextDataToSaveBytes);

        String filename = getFilenameForCredentialData(mCredentialName);
        AtomicFile file = new AtomicFile(new File(mStorageDirectory, filename));
        FileOutputStream outputStream = null;
        try {
            outputStream = file.startWrite();
            outputStream.write(dataToSaveBytes);
            file.finishWrite(outputStream);
        } catch (IOException e) {
            if (outputStream != null) {
                file.failWrite(outputStream);
            }
            throw new RuntimeException("Error writing data", e);
        }
    }

    private byte[] saveToDiskEncode(CborBuilder map) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CborEncoder encoder = new CborEncoder(baos).nonCanonical();
        try {
            encoder.encode(map.build());
        } catch (CborException e) {
            throw new RuntimeException("Error encoding data", e);
        }
        return baos.toByteArray();
    }

    final int CHUNKED_ENCRYPTED_MAX_CHUNK_SIZE = 16384;
    final String CHUNKED_ENCRYPTED_MAGIC = "ChunkedEncryptedData";

    private byte[] saveToDiskEncrypt(byte[] cleartextDataToSaveBytes) {
        // Because some older Android versions have a buggy Android Keystore where encryption
        // only works with small amounts of data (b/234563696) chop the cleartext into smaller
        // chunks and encrypt them separately. Store on disk as CBOR using the following CDDL
        //
        //  ChunkedEncryptedData = [
        //    "ChunkedEncryptedData",
        //    [+ bstr],
        //  ]
        //
        // so we can easily tell if the data on disk is from a version of the library that
        // does this. In particular it means the decryption routine can simply test if the
        // data loaded from disk has the string "ChunkedEncryptedData" at offset 2.
        //
        CborBuilder builder = new CborBuilder();
        ArrayBuilder<CborBuilder> arrayBuilder = builder.addArray();
        arrayBuilder.add(CHUNKED_ENCRYPTED_MAGIC);
        ArrayBuilder<ArrayBuilder<CborBuilder>> innerArrayBuilder = arrayBuilder.addArray();

        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            String dataKeyAlias = getDataKeyAliasFromCredentialName(mCredentialName);

            KeyStore.Entry entry = ks.getEntry(dataKeyAlias, null);
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();

            int offset = 0;
            boolean lastChunk = false;
            do {
                int chunkSize = cleartextDataToSaveBytes.length - offset;
                if (chunkSize <= CHUNKED_ENCRYPTED_MAX_CHUNK_SIZE) {
                    lastChunk = true;
                } else {
                    chunkSize = CHUNKED_ENCRYPTED_MAX_CHUNK_SIZE;
                }

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                // Produced cipherText includes auth tag
                byte[] cipherTextForChunk = cipher.doFinal(
                        cleartextDataToSaveBytes,
                        offset,
                        chunkSize);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baos.write(cipher.getIV());
                baos.write(cipherTextForChunk);
                byte[] cipherTextForChunkWithIV = baos.toByteArray();

                innerArrayBuilder.add(cipherTextForChunkWithIV);
                offset += chunkSize;
            } while (!lastChunk);
        } catch (NoSuchPaddingException
                | BadPaddingException
                | NoSuchAlgorithmException
                | CertificateException
                | InvalidKeyException
                | UnrecoverableEntryException
                | IOException
                | IllegalBlockSizeException
                | KeyStoreException e) {
            throw new RuntimeException("Error encrypting CBOR for saving to disk", e);
        }
        return Util.cborEncode(builder.build().get(0));
    }

    private void saveToDiskAuthKeys(MapBuilder<CborBuilder> map) {
        map.put("perReaderSessionKeyAlias", mPerReaderSessionKeyAlias);
        MapBuilder<MapBuilder<CborBuilder>> acpTimeoutKeyMapBuilder = map.putMap(
                "acpTimeoutKeyMap");
        for (Map.Entry<Integer, String> entry : mAcpTimeoutKeyAliases.entrySet()) {
            int profileId = entry.getKey();
            String acpAlias = entry.getValue();
            acpTimeoutKeyMapBuilder.put(new UnsignedInteger(profileId),
                    new UnicodeString(acpAlias));
        }
    }

    private void saveToDiskNamespaceDatas(MapBuilder<CborBuilder> map) {
        MapBuilder<MapBuilder<CborBuilder>> ensArrayBuilder = map.putMap(
                "namespaceDatas");
        for (PersonalizationData.NamespaceData namespaceData : mNamespaceDatas) {
            ensArrayBuilder.put(new UnicodeString(namespaceData.getNamespaceName()),
                    namespaceDataToCbor(namespaceData));
        }
    }

    private void saveToDiskACPs(MapBuilder<CborBuilder> map) {
        ArrayBuilder<MapBuilder<CborBuilder>> acpArrayBuilder = map.putArray(
                "accessControlProfiles");
        for (AccessControlProfile profile : mAccessControlProfiles) {
            acpArrayBuilder.add(accessControlProfileToCbor(profile));
        }
    }

    private void saveToDiskAuthDatas(MapBuilder<CborBuilder> map) {
        ArrayBuilder<MapBuilder<CborBuilder>> authKeyDataArrayBuilder = map.putArray(
                "authKeyDatas");
        for (AuthKeyData data : mAuthKeyDatas) {
            long expirationDateMillis = Long.MAX_VALUE;
            if (data.mExpirationDate != null) {
                expirationDateMillis = data.mExpirationDate.getTimeInMillis();
            }
            authKeyDataArrayBuilder.addMap()
                    .put("alias", data.mAlias)
                    .put("useCount", data.mUseCount)
                    .put("certificate", data.mCertificate)
                    .put("staticAuthenticationData", data.mStaticAuthenticationData)
                    .put("pendingAlias", data.mPendingAlias)
                    .put("pendingCertificate", data.mPendingCertificate)
                    .put("expirationDateMillis", expirationDateMillis)
                    .end();
        }
    }

    private void saveToDiskBasic(MapBuilder<CborBuilder> map) {
        map.put("docType", mDocType);
        map.put("credentialKeyAlias", mCredentialKeyAlias);
        ArrayBuilder<MapBuilder<CborBuilder>> credentialKeyCertChainBuilder =
                map.putArray("credentialKeyCertChain");
        for (X509Certificate certificate : mCertificateChain) {
            try {
                credentialKeyCertChainBuilder.add(certificate.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new RuntimeException("Error encoding certificate", e);
            }
        }
        map.put("proofOfProvisioningSha256", mProofOfProvisioningSha256);
        map.put("authKeyCount", mAuthKeyCount);
        map.put("authKeyMaxUses", mAuthMaxUsesPerKey);
        map.put("authKeyMinValidTimeMillis", mAuthKeyMinValidTimeMillis);
    }

    private boolean loadFromDisk(String dataKeyAlias) {
        String filename = getFilenameForCredentialData(mCredentialName);
        byte[] encryptedFileData = new byte[0];
        try {
            AtomicFile file = new AtomicFile(new File(mStorageDirectory, filename));
            encryptedFileData = file.readFully();
        } catch (IOException e) {
            return false;
        }

        byte[] fileData = loadFromDiskDecrypt(dataKeyAlias, encryptedFileData);

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fileData);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != 1) {
                throw new RuntimeException("Expected 1 item, found " + dataItems.size());
            }
            if (!(dataItems.get(0) instanceof co.nstant.in.cbor.model.Map)) {
                throw new RuntimeException("Item is not a map");
            }
            co.nstant.in.cbor.model.Map map = (co.nstant.in.cbor.model.Map) dataItems.get(0);

            loadBasic(map);
            loadCredentialKeyCertChain(map);
            loadProofOfProvisioningSha256(map);
            loadAccessControlProfiles(map);
            loadNamespaceDatas(map);
            loadAuthKey(map);

        } catch (CborException e) {
            throw new RuntimeException("Error decoding data", e);
        }
        return true;
    }

    private byte[] loadFromDiskDecryptChunkedEncrypted(String dataKeyAlias, byte[] encryptedFileData) {
        // In this case we're dealing with data from a version of the library that chunks the
        // data to encrypt and stores the ciphertexts in a CBOR structure as per the CDDL defined
        // in saveToDiskEncrypt() namely
        //
        //  ChunkedEncryptedData = [
        //    "ChunkedEncryptedData",
        //    [+ bstr],
        //  ]
        //
        ByteArrayInputStream bais = new ByteArrayInputStream(encryptedFileData);
        List<DataItem> dataItems;
        try {
            dataItems = new CborDecoder(bais).decode();
        } catch (CborException e) {
            throw new RuntimeException("Error decoding ChunkedEncryptedData CBOR");
        }
        if (dataItems.size() != 1) {
            throw new RuntimeException("Expected one item, found " + dataItems.size());
        }
        if (!(dataItems.get(0) instanceof Array)) {
            throw new RuntimeException("Item is not a array");
        }
        Array array = (co.nstant.in.cbor.model.Array) dataItems.get(0);
        if (array.getDataItems().size() < 2) {
            throw new RuntimeException("Expected 2+ items, found " + array.getDataItems().size());
        }
        if (!(array.getDataItems().get(1) instanceof Array)) {
            throw new RuntimeException("Second item in outer array is not a array");
        }
        Array innerArray = (co.nstant.in.cbor.model.Array) array.getDataItems().get(1);

        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(dataKeyAlias, null);
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (DataItem item : innerArray.getDataItems()) {
                if (!(item instanceof ByteString)) {
                    throw new RuntimeException("Item in inner array is not a bstr");
                }
                byte[] encryptedChunk = ((ByteString) item).getBytes();

                ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedChunk);
                byte[] iv = new byte[12];
                byteBuffer.get(iv);
                byte[] cipherText = new byte[encryptedChunk.length - 12];
                byteBuffer.get(cipherText);

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
                byte[] decryptedChunk = cipher.doFinal(cipherText);
                baos.write(decryptedChunk);
            }
            return baos.toByteArray();

        } catch (InvalidAlgorithmParameterException
                | NoSuchPaddingException
                | BadPaddingException
                | NoSuchAlgorithmException
                | CertificateException
                | InvalidKeyException
                | IOException
                | IllegalBlockSizeException
                | UnrecoverableEntryException
                | KeyStoreException e) {
            throw new RuntimeException("Error decrypting chunk", e);
        }
    }

    private byte[] loadFromDiskDecrypt(String dataKeyAlias, byte[] encryptedFileData) {
        // See saveToDiskEncrypt() for how newer versions of the library store the encrypted
        // data in chunks.
        final byte[] magic = CHUNKED_ENCRYPTED_MAGIC.getBytes(StandardCharsets.UTF_8);
        if (encryptedFileData.length >= 2 + magic.length) {
            if (Arrays.equals(Arrays.copyOfRange(encryptedFileData, 2, magic.length + 2),
                    magic)) {
                return loadFromDiskDecryptChunkedEncrypted(dataKeyAlias, encryptedFileData);
            }
        }

        byte[] fileData = null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(dataKeyAlias, null);
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();

            if (encryptedFileData.length < 12) {
                throw new RuntimeException("Encrypted CBOR on disk is too small");
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedFileData);
            byte[] iv = new byte[12];
            byteBuffer.get(iv);
            byte[] cipherText = new byte[encryptedFileData.length - 12];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            fileData = cipher.doFinal(cipherText);
        } catch (InvalidAlgorithmParameterException
                | NoSuchPaddingException
                | BadPaddingException
                | NoSuchAlgorithmException
                | CertificateException
                | InvalidKeyException
                | IOException
                | IllegalBlockSizeException
                | UnrecoverableEntryException
                | KeyStoreException e) {
            throw new RuntimeException("Error decrypting CBOR", e);
        }
        return fileData;
    }

    private void loadBasic(co.nstant.in.cbor.model.Map map) {
        mDocType = ((UnicodeString) map.get(new UnicodeString("docType"))).getString();
        mCredentialKeyAlias = ((UnicodeString) map.get(
                new UnicodeString("credentialKeyAlias"))).getString();
    }

    private void loadAuthKey(co.nstant.in.cbor.model.Map map) {
        mPerReaderSessionKeyAlias = ((UnicodeString) map.get(
                new UnicodeString("perReaderSessionKeyAlias"))).getString();

        DataItem userAuthKeyAliases = map.get(new UnicodeString("acpTimeoutKeyMap"));
        if (!(userAuthKeyAliases instanceof co.nstant.in.cbor.model.Map)) {
            throw new RuntimeException("acpTimeoutKeyMap not found or not map");
        }
        mAcpTimeoutKeyAliases = new HashMap<>();
        for (DataItem key : ((co.nstant.in.cbor.model.Map) userAuthKeyAliases).getKeys()) {
            if (!(key instanceof UnsignedInteger)) {
                throw new RuntimeException(
                        "Key in acpTimeoutKeyMap is not an integer");
            }
            int profileId = ((UnsignedInteger) key).getValue().intValue();
            DataItem item = ((co.nstant.in.cbor.model.Map) userAuthKeyAliases).get(key);
            if (!(item instanceof UnicodeString)) {
                throw new RuntimeException(
                        "Item in acpTimeoutKeyMap is not a string");
            }
            String acpAlias = ((UnicodeString) item).getString();
            mAcpTimeoutKeyAliases.put(profileId, acpAlias);
        }

        mAuthKeyCount = ((Number) map.get(
                new UnicodeString("authKeyCount"))).getValue().intValue();

        mAuthMaxUsesPerKey = ((Number) map.get(
                new UnicodeString("authKeyMaxUses"))).getValue().intValue();

        mAuthKeyMinValidTimeMillis = 0;
        if (Util.cborMapHasKey(map, "authKeyMinValidTimeMillis")) {
            mAuthKeyMinValidTimeMillis = ((Number) map.get(
                    new UnicodeString("authKeyMinValidTimeMillis"))).getValue().intValue();
        }

        DataItem authKeyDatas = map.get(new UnicodeString("authKeyDatas"));
        if (!(authKeyDatas instanceof Array)) {
            throw new RuntimeException("authKeyDatas not found or not array");
        }
        mAuthKeyDatas = new ArrayList<AuthKeyData>();
        for (DataItem item : ((Array) authKeyDatas).getDataItems()) {
            AuthKeyData data = new AuthKeyData();

            co.nstant.in.cbor.model.Map im = (co.nstant.in.cbor.model.Map) item;

            data.mAlias = ((UnicodeString) im.get(new UnicodeString("alias"))).getString();
            data.mUseCount = ((Number) im.get(new UnicodeString("useCount"))).getValue().intValue();
            data.mCertificate = ((ByteString) im.get(new UnicodeString("certificate"))).getBytes();
            data.mStaticAuthenticationData = ((ByteString) im.get(
                    new UnicodeString("staticAuthenticationData"))).getBytes();
            data.mPendingAlias = ((UnicodeString) im.get(
                    new UnicodeString("pendingAlias"))).getString();
            data.mPendingCertificate = ((ByteString) im.get(
                    new UnicodeString("pendingCertificate"))).getBytes();

            // expirationDateMillis was added in a later release, may not be present
            long expirationDateMillis = Long.MAX_VALUE;
            DataItem expirationDateMillisItem = im.get(new UnicodeString("expirationDateMillis"));
            if (expirationDateMillisItem != null) {
                if (!(expirationDateMillisItem instanceof Number)) {
                    throw new RuntimeException("expirationDateMillis not a number");
                }
                expirationDateMillis = Util.checkedLongValue(expirationDateMillisItem);
            }
            Calendar expirationDate = Calendar.getInstance();
            expirationDate.setTimeInMillis(expirationDateMillis);
            data.mExpirationDate = expirationDate;

            mAuthKeyDatas.add(data);
        }
    }

    private void loadNamespaceDatas(co.nstant.in.cbor.model.Map map) {
        DataItem namespaceDatas = map.get(new UnicodeString("namespaceDatas"));
        if (!(namespaceDatas instanceof co.nstant.in.cbor.model.Map)) {
            throw new RuntimeException("namespaceDatas not found or not map");
        }
        mNamespaceDatas = new ArrayList<PersonalizationData.NamespaceData>();
        for (DataItem key : ((co.nstant.in.cbor.model.Map) namespaceDatas).getKeys()) {
            if (!(key instanceof UnicodeString)) {
                throw new RuntimeException("Key in namespaceDatas is not a string");
            }
            String namespaceName = ((UnicodeString) key).getString();
            DataItem item = ((co.nstant.in.cbor.model.Map) namespaceDatas).get(key);
            mNamespaceDatas.add(namespaceDataFromCbor(namespaceName, item));
        }
    }

    private void loadAccessControlProfiles(co.nstant.in.cbor.model.Map map) {
        DataItem accessControlProfiles = map.get(new UnicodeString("accessControlProfiles"));
        if (!(accessControlProfiles instanceof Array)) {
            throw new RuntimeException(
                    "accessControlProfiles not found or not array");
        }
        mAccessControlProfiles = new ArrayList<AccessControlProfile>();
        mProfileIdToAcpMap = new HashMap<Integer, AccessControlProfile>();
        for (DataItem item : ((Array) accessControlProfiles).getDataItems()) {
            AccessControlProfile profile = accessControlProfileFromCbor(item);
            mAccessControlProfiles.add(profile);
            mProfileIdToAcpMap.put(profile.getAccessControlProfileId().getId(), profile);
        }
    }

    private void loadProofOfProvisioningSha256(co.nstant.in.cbor.model.Map map) {
        DataItem proofOfProvisioningSha256 = map.get(
                new UnicodeString("proofOfProvisioningSha256"));
        if (!(proofOfProvisioningSha256 instanceof ByteString)) {
            throw new RuntimeException(
                    "proofOfProvisioningSha256 not found or not bstr");
        }
        mProofOfProvisioningSha256 = ((ByteString) proofOfProvisioningSha256).getBytes();
    }

    private void loadCredentialKeyCertChain(co.nstant.in.cbor.model.Map map) {
        DataItem credentialKeyCertChain = map.get(new UnicodeString("credentialKeyCertChain"));
        if (!(credentialKeyCertChain instanceof Array)) {
            throw new RuntimeException(
                    "credentialKeyCertChain not found or not array");
        }
        mCertificateChain = new ArrayList<>();
        for (DataItem item : ((Array) credentialKeyCertChain).getDataItems()) {
            byte[] encodedCert = ((ByteString) item).getBytes();
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                ByteArrayInputStream certBais = new ByteArrayInputStream(encodedCert);
                mCertificateChain.add((X509Certificate) cf.generateCertificate(certBais));
            } catch (CertificateException e) {
                throw new RuntimeException("Error decoding certificate blob", e);
            }
        }
    }

    Collection<AccessControlProfile> getAccessControlProfiles() {
        return mAccessControlProfiles;
    }

    Collection<PersonalizationData.NamespaceData> getNamespaceDatas() {
        return mNamespaceDatas;
    }

    PersonalizationData.NamespaceData lookupNamespaceData(String nameSpace) {
        // TODO: This might be slow, maybe build map at load/build time
        for (PersonalizationData.NamespaceData namespaceData : mNamespaceDatas) {
            if (namespaceData.getNamespaceName().equals(nameSpace)) {
                return namespaceData;
            }
        }
        return null;
    }

    String getCredentialKeyAlias() {
        return mCredentialKeyAlias;
    }

    String getPerReaderSessionKeyAlias() {
        return mPerReaderSessionKeyAlias;
    }

    int getAuthKeyCount() {
        return mAuthKeyCount;
    }

    int getAuthMaxUsesPerKey() {
        return mAuthMaxUsesPerKey;
    }

    long getAuthKeyMinValidTimeMillis() {
        return mAuthKeyMinValidTimeMillis;
    }

    int[] getAuthKeyUseCounts() {
        int[] result = new int[mAuthKeyCount];
        int n = 0;
        for (AuthKeyData data : mAuthKeyDatas) {
            result[n++] = data.mUseCount;
        }
        return result;
    }

    List<Calendar> getAuthKeyExpirations() {
        List<Calendar> ret = new ArrayList<>();
        for (AuthKeyData data : mAuthKeyDatas) {
            ret.add(data.mExpirationDate);
        }
        return ret;
    }

    void setAvailableAuthenticationKeys(int keyCount, int maxUsesPerKey, long minValidTimeMillis) {
        int prevAuthKeyCount = mAuthKeyCount;
        mAuthKeyCount = keyCount;
        mAuthMaxUsesPerKey = maxUsesPerKey;
        mAuthKeyMinValidTimeMillis = minValidTimeMillis;

        if (prevAuthKeyCount < mAuthKeyCount) {
            // Added non-zero number of auth keys...
            for (int n = prevAuthKeyCount; n < mAuthKeyCount; n++) {
                mAuthKeyDatas.add(new AuthKeyData());
            }
        } else if (prevAuthKeyCount > mAuthKeyCount) {
            KeyStore ks = null;
            try {
                ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
            } catch (CertificateException
                    | IOException
                    | NoSuchAlgorithmException
                    | KeyStoreException e) {
                throw new RuntimeException("Error loading keystore", e);
            }

            int numKeysToDelete = prevAuthKeyCount - mAuthKeyCount;
            // Removed non-zero number of auth keys. For now we just delete
            // the keys at the beginning... (an optimization could be to instead
            // delete the keys with the biggest use count).
            for (int n = 0; n < numKeysToDelete; n++) {
                AuthKeyData data = mAuthKeyDatas.get(0);
                if (!data.mAlias.isEmpty()) {
                    try {
                        if (ks.containsAlias(data.mAlias)) {
                            ks.deleteEntry(data.mAlias);
                        }
                    } catch (KeyStoreException e) {
                        throw new RuntimeException(
                                "Error deleting auth key with mAlias " + data.mAlias, e);
                    }
                }
                if (!data.mPendingAlias.isEmpty()) {
                    try {
                        if (ks.containsAlias(data.mPendingAlias)) {
                            ks.deleteEntry(data.mPendingAlias);
                        }
                    } catch (KeyStoreException e) {
                        throw new RuntimeException(
                                "Error deleting auth key with mPendingAlias " + data.mPendingAlias,
                                e);
                    }
                }
                mAuthKeyDatas.remove(0);
            }
        }
        saveToDisk();
    }

    List<List<X509Certificate>> getAuthKeyChainsNeedingCertification(byte[] challenge) {
        KeyStore ks = loadKeyStore();

        ArrayList<List<X509Certificate>> chains = new ArrayList<>();

        // Determine which keys need certification (or re-certification) and generate
        // keys and X.509 certs for these and mark them as pending.
        for (int n = 0; n < mAuthKeyCount; n++) {
            AuthKeyData data = mAuthKeyDatas.get(n);

            boolean newKeyNeeded = isNewKeyNeeded(data);
            boolean certificationPending = !data.mPendingAlias.isEmpty();

            if (newKeyNeeded && !certificationPending) {
                try {
                    String aliasForAuthKey = generateAliasForAuthKey(n, data.mAlias);

                    generateKeyPairForAuthKey(aliasForAuthKey, challenge);

                    data.mPendingAlias = aliasForAuthKey;
                    certificationPending = true;
                } catch (InvalidAlgorithmParameterException
                         | NoSuchAlgorithmException
                         | NoSuchProviderException e) {
                    throw new RuntimeException("Error creating auth key", e);
                }
            }

            if (certificationPending) {
                try {
                    Certificate[] chain = ks.getCertificateChain(data.mPendingAlias);
                    List<X509Certificate> list = new ArrayList<>();
                    for (Certificate cert : chain) {
                        list.add((X509Certificate) cert);
                    }
                    chains.add(list);
                } catch (KeyStoreException e) {
                    throw new RuntimeException(
                        "Error creating certificate for auth key", e);
                }
            }
        }

        saveToDisk();

        return chains;
    }

    Collection<X509Certificate> getAuthKeysNeedingCertification() {
        KeyStore ks = loadKeyStore();

        ArrayList<X509Certificate> certificates = new ArrayList<X509Certificate>();

        // Determine which keys need certification (or re-certification) and generate
        // keys and X.509 certs for these and mark them as pending.
        for (int n = 0; n < mAuthKeyCount; n++) {
            AuthKeyData data = mAuthKeyDatas.get(n);

            boolean newKeyNeeded = isNewKeyNeeded(data);
            boolean certificationPending = !data.mPendingAlias.isEmpty();

            if (newKeyNeeded && !certificationPending) {
                try {
                    String aliasForAuthKey = generateAliasForAuthKey(n, data.mAlias);

                    generateKeyPairForAuthKey(aliasForAuthKey,
                        new byte[]{}); ///* null challenge as leaf cert will be ignored and regenerated later*/

                    X509Certificate certificate = generateAuthenticationKeyCert(
                        aliasForAuthKey, mCredentialKeyAlias, mProofOfProvisioningSha256);

                    data.mPendingAlias = aliasForAuthKey;
                    data.mPendingCertificate = certificate.getEncoded();
                    certificationPending = true;
                } catch (InvalidAlgorithmParameterException
                         | NoSuchAlgorithmException
                         | NoSuchProviderException
                         | CertificateEncodingException e) {
                    throw new RuntimeException("Error creating auth key", e);
                }
            }

            if (certificationPending) {
                try {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    ByteArrayInputStream bais = new ByteArrayInputStream(data.mPendingCertificate);
                    certificates.add((X509Certificate) cf.generateCertificate(bais));
                } catch (CertificateException e) {
                    throw new RuntimeException(
                        "Error creating certificate for auth key", e);
                }
            }
        }

        saveToDisk();

        return certificates;
    }

    private void generateKeyPairForAuthKey(String aliasForAuthKey, byte[] challenge)
        throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
            aliasForAuthKey,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512);

        // setAttestationChallenge is only supported on >= API level 24
        boolean isSetAttestationChallengeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        if (challenge != null && isSetAttestationChallengeSupported) {
            builder.setAttestationChallenge(challenge);
        }

        boolean isStrongBoxBacked = false;
        /* Disable StrongBox usage for now, see Issue #259 for details
         *
        PackageManager pm = mContext.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            isStrongBoxBacked = true;
            builder.setIsStrongBoxBacked(true);
        }
         */
        kpg.initialize(builder.build());
        kpg.generateKeyPair();
        Logger.INSTANCE.i(TAG, "AuthKey created, strongBoxBacked=" + isStrongBoxBacked);
    }

    private String generateAliasForAuthKey(int n, String currentAlias) {
        // Calculate name to use and be careful to avoid collisions when
        // re-certifying an already populated slot.
        String aliasForAuthKey = mCredentialKeyAlias + String.format(Locale.US,
            "_auth_%d", n);
        if (aliasForAuthKey.equals(currentAlias)) {
            aliasForAuthKey = aliasForAuthKey + "_";
        }
        return aliasForAuthKey;
    }

    private boolean isNewKeyNeeded(AuthKeyData data) {
        Calendar now = Calendar.getInstance();
        boolean keyExceededUseCount = (data.mUseCount >= mAuthMaxUsesPerKey);
        boolean keyBeyondExpirationDate = false;
        if (data.mExpirationDate != null) {
            // Adjust expiration date for the minimum amount of time required in order
            // for an auth key to be considered valid.
            Calendar expirationDateAdjusted = (Calendar) data.mExpirationDate.clone();
            expirationDateAdjusted.add(Calendar.MILLISECOND, (int) -mAuthKeyMinValidTimeMillis);
            keyBeyondExpirationDate = now.after(expirationDateAdjusted);
        }
        return data.mAlias.isEmpty() || keyExceededUseCount || keyBeyondExpirationDate;
    }

    void storeStaticAuthenticationData(X509Certificate authenticationKey,
            Calendar expirationDate,
            byte[] staticAuthData)
            throws UnknownAuthenticationKeyException {
        AuthKeyData dataForAuthKey = null;
        CertificateFactory cf = null;
        try {
            cf = CertificateFactory.getInstance("X.509");

            for (AuthKeyData data : mAuthKeyDatas) {
                if (data.mPendingCertificate.length > 0) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(data.mPendingCertificate);
                    X509Certificate certificate = (X509Certificate) cf.generateCertificate(bais);
                    if (certificate.equals(authenticationKey)) {
                        dataForAuthKey = data;
                        break;
                    }
                }
            }
        } catch (CertificateException e) {
            throw new RuntimeException("Error encoding certificate", e);
        }

        if (dataForAuthKey == null) {
            throw new UnknownAuthenticationKeyException("No such authentication key");
        }

        KeyStore ks = loadKeyStore();
        updateAuthKeyData(dataForAuthKey, expirationDate, staticAuthData, ks);
    }

    void storeStaticAuthenticationData(@NonNull PublicKey pendingAuthKey,
        Calendar expirationDate,
        byte[] staticAuthData)
        throws UnknownAuthenticationKeyException {
        AuthKeyData dataForAuthKey = null;
        KeyStore ks = loadKeyStore();

        for (AuthKeyData data : mAuthKeyDatas) {
            if (data.mPendingAlias != null && !data.mPendingAlias.isEmpty()) {
                // Get the certificate from Keystore using data.mPendingAlias and compare its
                // public key with the public key of the provided authenticationKey.
                PublicKey keystorePublicKey;
                try {
                    keystorePublicKey = getPublicKeyFromKeystoreAlias(ks, data.mPendingAlias);
                } catch (KeyStoreException e) {
                    throw new RuntimeException("Error loading penidng auth key from keystore", e);
                }

                if (keystorePublicKey.equals(pendingAuthKey)) {
                    dataForAuthKey = data;
                    break;
                }
            }
        }

        if (dataForAuthKey == null) {
            throw new UnknownAuthenticationKeyException("No such authentication key");
        }

        updateAuthKeyData(dataForAuthKey, expirationDate, staticAuthData, ks);
    }

    private void updateAuthKeyData(@NonNull AuthKeyData dataForAuthKey,
        @NonNull Calendar expirationDate,
        @NonNull byte[] staticAuthData,
        @NonNull KeyStore ks) {
        // Delete old key, if set.
        if (!dataForAuthKey.mAlias.isEmpty()) {
            try {
                if (ks.containsAlias(dataForAuthKey.mAlias)) {
                    ks.deleteEntry(dataForAuthKey.mAlias);
                }
            } catch (KeyStoreException e) {
                throw new RuntimeException("Error deleting old authentication key", e);
            }
        }
        dataForAuthKey.mAlias = dataForAuthKey.mPendingAlias;
        dataForAuthKey.mCertificate = dataForAuthKey.mPendingCertificate;
        dataForAuthKey.mStaticAuthenticationData = staticAuthData;
        dataForAuthKey.mUseCount = 0;
        dataForAuthKey.mPendingAlias = "";
        dataForAuthKey.mPendingCertificate = new byte[0];
        dataForAuthKey.mExpirationDate = expirationDate;
        saveToDisk();
    }

    private PublicKey getPublicKeyFromKeystoreAlias(@NonNull KeyStore ks, @NonNull String alias) throws KeyStoreException {
        if (!ks.containsAlias(alias)) {
            throw new KeyStoreException("Key not found in Keystore");
        }
        Certificate keystoreCert = ks.getCertificate(alias);
        if (keystoreCert == null) {
            throw new KeyStoreException("No certificate for this alias");
        }
        return keystoreCert.getPublicKey();
    }

    /**
     * Selects an authentication key to use.
     *
     * The victim is picked simply by choosing the key with the smallest use count. (This may
     * change in the future.)
     *
     * The use count of the returned authentication key will be increased by one.
     *
     * If no key could be found {@code null} is returned.
     *
     * @param allowUsingExhaustedKeys If {@code true}, allow using an authentication key which
     *                                use count has been exceeded if no other key is available.
     * @param allowUsingExpiredKeys If {@code true}, allow using an authentication key which
     *                              is expired.
     * @param incrementKeyUsageCount if {@code false}, usage count will not be increased.
     * @return A pair containing the authentication key and its associated static authentication
     * data or {@code null} if no key could be found.
     */
    Pair<PrivateKey, byte[]> selectAuthenticationKey(boolean allowUsingExhaustedKeys,
            boolean allowUsingExpiredKeys,
            boolean incrementKeyUsageCount) {

        // First try to find a un-expired key..
        Pair<PrivateKey, byte[]> keyAndStaticData =
                selectAuthenticationKeyHelper(allowUsingExhaustedKeys, false,
                        incrementKeyUsageCount);
        if (keyAndStaticData != null) {
            return keyAndStaticData;
        }
        // Nope, try to see if there's an expired key (if allowed)
        if (!allowUsingExpiredKeys) {
            return null;
        }
        return selectAuthenticationKeyHelper(allowUsingExhaustedKeys, true,
                incrementKeyUsageCount);
    }

    Pair<PrivateKey, byte[]> selectAuthenticationKeyHelper(boolean allowUsingExhaustedKeys,
            boolean allowUsingExpiredKeys,
            boolean incrementKeyUsageCount) {
        AuthKeyData candidate = null;

        Calendar now = Calendar.getInstance();

        for (int n = 0; n < mAuthKeyCount; n++) {
            AuthKeyData data = mAuthKeyDatas.get(n);
            if (!data.mAlias.isEmpty()) {
                if (data.mExpirationDate != null) {
                    if (now.after(data.mExpirationDate)) {
                        // expired...
                        if (!allowUsingExpiredKeys) {
                            continue;
                        }
                    }
                }

                if (candidate == null || data.mUseCount < candidate.mUseCount) {
                    candidate = data;
                }
            }
        }

        if (candidate == null) {
            return null;
        }

        // We've found the key with lowest use count... so if this is beyond maximum uses
        // so are all the other ones. So fail if we're not allowed to use exhausted keys.
        if (candidate.mUseCount >= mAuthMaxUsesPerKey) {
            if (!allowUsingExhaustedKeys) {
                return null;
            }

            Logger.INSTANCE.i(TAG, "Using exhausted key.");
        }

        KeyStore.Entry entry = null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            entry = ks.getEntry(candidate.mAlias, null);
        } catch (CertificateException
                | IOException
                | NoSuchAlgorithmException
                | KeyStoreException
                | UnrecoverableEntryException e) {
            throw new RuntimeException("Error loading keystore", e);
        }

        Pair<PrivateKey, byte[]> result = new Pair<>(
                ((KeyStore.PrivateKeyEntry) entry).getPrivateKey(),
                candidate.mStaticAuthenticationData);

        if (incrementKeyUsageCount) {
            candidate.mUseCount += 1;
            saveToDisk();
        }

        return result;
    }

    String getDocType() {
        return mDocType;
    }

    Collection<X509Certificate> getCredentialKeyCertificateChain() {
        return mCertificateChain;
    }

    AccessControlProfile getAccessControlProfile(AccessControlProfileId profileId) {
        AccessControlProfile profile = mProfileIdToAcpMap.get(profileId.getId());
        if (profile == null) {
            throw new RuntimeException("No profile with id " + profileId.getId());
        }
        return profile;
    }

    private boolean checkUserAuthenticationTimeout(String acpAlias) {
        // Unfortunately there are no APIs to tell if a key needs user authentication to work so
        // we check if the key is available by simply trying to encrypt some data.
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(acpAlias, null);
            if (entry == null) {
                throw new CredentialInvalidatedException("Failed getting key used for credential");
            }
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] clearText = {0x01, 0x02};
            cipher.doFinal(clearText);
            // We don't care about the cipherText, only whether the key is unlocked.
        } catch (UserNotAuthenticatedException e) {
            return false;
        } catch (Throwable e) {
            // If this fails, it probably means authentication is needed...
            Logger.INSTANCE.w(TAG, "Unexpected exception `" + e.getMessage()
                    + "`, assuming user not authenticated");
            return false;
        }
        return true;
    }

    boolean checkUserAuthentication(AccessControlProfileId accessControlProfileId,
            boolean didUserAuth) {
        AccessControlProfile profile = getAccessControlProfile(accessControlProfileId);
        if (profile.getUserAuthenticationTimeout() == 0) {
            return didUserAuth;
        }
        String acpAlias = mAcpTimeoutKeyAliases.get(accessControlProfileId.getId());
        if (acpAlias == null) {
            throw new RuntimeException(
                    "No key alias for ACP with ID " + accessControlProfileId.getId());
        }
        return checkUserAuthenticationTimeout(acpAlias);
    }

    @NonNull
    List<kotlin.Pair<String, byte[]>> getAuthenticationKeys() {
        ArrayList<kotlin.Pair<String, byte[]>> ret = new ArrayList<>();
        for (AuthKeyData data : mAuthKeyDatas) {
            if (!data.mAlias.isEmpty()) {
                ret.add(new kotlin.Pair<>(data.mAlias, data.mStaticAuthenticationData));
            }
        }
        return ret;
    }

    // Note that a dynamic authentication key may have two Android Keystore keys associated with
    // it.. the obvious one is for a previously certificated key. This key may possibly have an
    // use-count which is already exhausted. The other one is for a key yet pending certification.
    //
    // Why is it implemented this way? Because we never want selectAuthenticationKey() to fail.
    // That is, it's better to use a key with an exhausted use-count (slightly bad for user privacy
    // in terms of linkability between multiple presentations) than the user not being able to
    // present their credential at all...
    static class AuthKeyData {
        // The mAlias for the key in Android Keystore. Is set to the empty string if the key has not
        // yet been created. This is set to the empty string if no key has been certified.
        String mAlias = "";
        // The X509 certificate for the key. This is empty if mAlias is empty.
        byte[] mCertificate = new byte[0];
        // The static authentication data, as set by the application as part of certification. This
        // is empty if mAlias is empty.
        byte[] mStaticAuthenticationData = new byte[0];
        // The number of times a key has been used.
        int mUseCount = 0;
        // The alias for a key pending certification. Once a key has been certified - by
        // calling storeStaticAuthenticationData() - and is no longer pending, both mPendingAlias
        // and mPendingCertificate will be set to the empty string and empty byte array.
        String mPendingAlias = "";
        // The X509 certificate for a key pending certification.
        byte[] mPendingCertificate = new byte[0];

        Calendar mExpirationDate = null;

        AuthKeyData() {
        }
    }
}
