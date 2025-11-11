/*
 * Copyright 2021 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.icu.util.Calendar;
import android.os.SystemClock;

import androidx.test.filters.LargeTest;

import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.multipaz.crypto.EcCurve;
import org.multipaz.crypto.X509Cert;
import org.multipaz.util.AndroidAttestationExtensionParser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import kotlin.Pair;
import kotlinx.io.bytestring.ByteString;

@LargeTest
@RunWith(Parameterized.class)
// TODO: We currently suppress deprecation warnings at this level (instead of at each call-site)
//  since a lot of the tests use API on IdentityCredential which is deprecated in favor of
//  PresentationSession. We should port the tests to use the new PresentationSession API instead.
@SuppressWarnings("deprecation")
public class DynamicAuthTest {
    private static final String TAG = "DynamicAuthTest";

    private final boolean useNewStoreStaticAuthDataMethod;

    public DynamicAuthTest(boolean useNewStoreStaticAuthDataMethod) {
        this.useNewStoreStaticAuthDataMethod = useNewStoreStaticAuthDataMethod;
    }

    @Parameters(name = "{index}: Test with useNewStoreStaticAuthDataMethod={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {false},
            {true}
        });
    }

    // Do NOT add BouncyCastle at setup time - we want to run tests against the normal AndroidOpenSSL JCA provider

    @SuppressWarnings("deprecation")
    @Test
    public void checkAuthKey() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);

        WritableIdentityCredential wc = store.createCredential(credentialName,
                "org.iso.18013-5.2019.mdl");

        byte[] challenge = "TheChallenge".getBytes();
        Collection<X509Certificate> certChain = wc.getCredentialKeyCertificateChain(challenge);
        // Profile 0 (no authentication)
        AccessControlProfile noAuthProfile =
                new AccessControlProfile.Builder(new AccessControlProfileId(0))
                        .setUserAuthenticationRequired(false)
                        .build();
        Collection<AccessControlProfileId> idsNoAuth = new ArrayList<AccessControlProfileId>();
        idsNoAuth.add(new AccessControlProfileId(0));
        String mdlNs = "org.iso.18013-5.2019";
        PersonalizationData personalizationData =
                new PersonalizationData.Builder()
                        .addAccessControlProfile(noAuthProfile)
                        .putEntry(mdlNs, "First name", idsNoAuth, Util.cborEncodeString("Alan"))
                        .putEntry(mdlNs, "Last name", idsNoAuth, Util.cborEncodeString("Turing"))
                        .build();
        byte[] proofOfProvisioningSignature = wc.personalize(personalizationData);
        byte[] proofOfProvisioning =
                Util.coseSign1GetData(Util.cborDecode(proofOfProvisioningSignature));
        byte[] proofOfProvisioningSha256 =
                MessageDigest.getInstance("SHA256").digest(proofOfProvisioning);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);
        credential.setAvailableAuthenticationKeys(5, 3);
        assertArrayEquals(
                new int[]{0, 0, 0, 0, 0},
                credential.getAuthenticationDataUsageCount());

        Collection<X509Certificate> certificates = null;
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certificates.size());

        X509Certificate cert = (X509Certificate) certificates.toArray()[0];

        //  - serialNumber: INTEGER 1 (fixed value: same on all certs).
        assertEquals(1, cert.getSerialNumber().intValue());

        //  - issuer: CN shall be set to "Android Identity Credential Key". (fixed value:
        //    same on all certs)
        assertEquals("CN=Android Identity Credential Key",
                cert.getIssuerX500Principal().getName());

        //  - subject: CN shall be set to "Android Identity Credential Authentication Key". (fixed
        //    value: same on all certs)
        assertEquals("CN=Android Identity Credential Authentication Key",
                cert.getSubjectX500Principal().getName());

        //  - validity: should be from current time and one year in the future (365 days).
        Date now = new Date();

        // Allow for 10 seconds drift to account for the time drift and loss of precision
        // when encoding into ASN.1
        //
        long diffMilliSecs = now.getTime() - cert.getNotBefore().getTime();
        final long allowDriftMilliSecs = 10 * 1000;
        assertTrue(-allowDriftMilliSecs <= diffMilliSecs && diffMilliSecs <= allowDriftMilliSecs);

        final long kMilliSecsInOneYear = 365L * 24 * 60 * 60 * 1000;
        diffMilliSecs =
                cert.getNotBefore().getTime() + kMilliSecsInOneYear - cert.getNotAfter().getTime();
        assertTrue(-allowDriftMilliSecs <= diffMilliSecs && diffMilliSecs <= allowDriftMilliSecs);

        // The extension must be there if the underlying hardware says it
        // supports updating the credential.
        //
        if (store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202101) {
            byte[] icExtension = cert.getExtensionValue("1.3.6.1.4.1.11129.2.1.26");
            assertNotNull(icExtension);
            assertArrayEquals(proofOfProvisioningSha256, Util.getPopSha256FromAuthKeyCert(cert));
        }

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void checkAuthKeyChains() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);

        WritableIdentityCredential wc = store.createCredential(credentialName,
            "org.iso.18013-5.2019.mdl");

        byte[] challenge = "TheChallenge".getBytes();
        // Profile 0 (no authentication)
        AccessControlProfile noAuthProfile =
            new AccessControlProfile.Builder(new AccessControlProfileId(0))
                .setUserAuthenticationRequired(false)
                .build();
        Collection<AccessControlProfileId> idsNoAuth = new ArrayList<AccessControlProfileId>();
        idsNoAuth.add(new AccessControlProfileId(0));
        String mdlNs = "org.iso.18013-5.2019";
        PersonalizationData personalizationData =
            new PersonalizationData.Builder()
                .addAccessControlProfile(noAuthProfile)
                .putEntry(mdlNs, "First name", idsNoAuth, Util.cborEncodeString("Alan"))
                .putEntry(mdlNs, "Last name", idsNoAuth, Util.cborEncodeString("Turing"))
                .build();
        wc.personalize(personalizationData);

        IdentityCredential credential = store.getCredentialByName(credentialName,
            IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);
        credential.setAvailableAuthenticationKeys(5, 3);
        assertArrayEquals(
            new int[]{0, 0, 0, 0, 0},
            credential.getAuthenticationDataUsageCount());

        List<List<X509Certificate>> certificateChains = null;
        certificateChains = credential.getAuthKeyChainsNeedingCertification(challenge);

        assertEquals(5, certificateChains.size());

        for (List<X509Certificate> certificateChain: certificateChains) {
            byte[] encoded = certificateChain.get(0).getEncoded();
            AndroidAttestationExtensionParser parser = new AndroidAttestationExtensionParser(
                new X509Cert(new ByteString(encoded, 0, encoded.length)));
            // just testing one element from chain to confirm that the attestation extension is present.
            assertArrayEquals(parser.getAttestationChallenge(), challenge);
        }

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void dynamicAuthTest() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);
        assertArrayEquals(new int[0], credential.getAuthenticationDataUsageCount());

        credential.setAvailableAuthenticationKeys(5, 3);
        assertArrayEquals(
                new int[]{0, 0, 0, 0, 0},
                credential.getAuthenticationDataUsageCount());

        // Getting data without device authentication should work even in the case where we haven't
        // provisioned any authentication keys. Check that.
        Map<String, Collection<String>> entriesToRequest = new LinkedHashMap<>();
        entriesToRequest.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
        // no setSessionTranscript() call indicates Device Authentication not requested.
        ResultData rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        byte[] resultCbor = rd.getAuthenticatedData();
        String pretty = Util.cborPrettyPrint(Util.canonicalizeCbor(resultCbor));
        assertEquals("{\n"
                        + "  'org.iso.18013-5.2019' : {\n"
                        + "    'Last name' : 'Turing',\n"
                        + "    'First name' : 'Alan'\n"
                        + "  }\n"
                        + "}",
                pretty);

        KeyPair readerEphemeralKeyPair = Util.createEphemeralKeyPair(EcCurve.P256);

        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        KeyPair ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());

        byte[] sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        // Then check that getEntries() throw NoAuthenticationKeyAvailableException (_even_ when
        // allowing using exhausted keys).
        try {
            credential.setSessionTranscript(sessionTranscript);
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
            fail();
        } catch (NoAuthenticationKeyAvailableException e) {
            // This is the expected path...
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }

        // Get auth keys needing certification. This should be all of them. Note that
        // this forces the creation of the authentication keys in the HAL.
        Collection<X509Certificate> certificates = null;
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certificates.size());

        // Do it one more time to check that an auth key is still pending even
        // when the corresponding key has been created.
        Collection<X509Certificate> certificates2 = null;
        certificates2 = credential.getAuthKeysNeedingCertification();
        assertArrayEquals(certificates.toArray(), certificates2.toArray());

        // Now set auth data for the *first* key (this is the act of certifying the key) and check
        // that one less key now needs certification.
        X509Certificate key0Cert = certificates.iterator().next();

        // Check key0Cert is signed by CredentialKey.
        try {
            key0Cert.verify(certChain.iterator().next().getPublicKey());
        } catch (CertificateException
                | InvalidKeyException
                | NoSuchAlgorithmException
                | NoSuchProviderException
                | SignatureException e) {
            e.printStackTrace();
            fail();
        }

        try {
            storeStaticAuthenticationData(credential, key0Cert, new byte[]{42, 43, 44});
            certificates = credential.getAuthKeysNeedingCertification();
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        assertEquals(4, certificates.size());

        // Now certify the *last* key.
        X509Certificate key4Cert = new ArrayList<X509Certificate>(certificates).get(
                certificates.size() - 1);
        try {
            key4Cert.verify(certChain.iterator().next().getPublicKey());
        } catch (CertificateException
                | InvalidKeyException
                | NoSuchAlgorithmException
                | NoSuchProviderException
                | SignatureException e) {
            e.printStackTrace();
            fail();
        }

        try {
            storeStaticAuthenticationData(credential, key4Cert, new byte[]{43, 44, 45});
            certificates = credential.getAuthKeysNeedingCertification();
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        assertEquals(3, certificates.size());

        // Now that we've provisioned authentication keys, presentation will no longer fail with
        // NoAuthenticationKeyAvailableException ... So now we can try a sessionTranscript without
        // the ephemeral public key that was created in the Secure Area and check it fails with
        // EphemeralPublicKeyNotFoundException instead...
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ByteArrayOutputStream stBaos = new ByteArrayOutputStream();
        try {
            new CborEncoder(stBaos).encode(new CborBuilder()
                    .addArray()
                    .add(new byte[]{0x01, 0x02})  // The device engagement structure, encoded
                    .add(new byte[]{0x03, 0x04})  // Reader ephemeral public key, encoded
                    .end()
                    .build());
        } catch (CborException e) {
            e.printStackTrace();
            fail();
        }
        /* TODO
        byte[] wrongSessionTranscript = stBaos.toByteArray();
        try {
            credential.setSessionTranscript(wrongSessionTranscript);
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
            assertTrue(false);
        } catch (EphemeralPublicKeyNotFoundException e) {
            // This is the expected path...
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            assertTrue(false);
        }
        */

        // Check we can get the authentication keys and associated auth data...
        List<Pair<String, byte[]>> listOfAuthKeys = ((KeystoreIdentityCredential) credential).getAuthenticationKeys();
        assertEquals(2, listOfAuthKeys.size());
        assertArrayEquals(new byte[] {42, 43, 44}, listOfAuthKeys.get(0).getSecond());
        assertArrayEquals(new byte[] {43, 44, 45}, listOfAuthKeys.get(1).getSecond());

        // Now use one of the keys...
        entriesToRequest = new LinkedHashMap<>();
        entriesToRequest.put("org.iso.18013-5.2019",
                Arrays.asList("First name",
                        "Last name",
                        "Home address",
                        "Birth date",
                        "Cryptanalyst",
                        "Portrait image",
                        "Height"));
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        resultCbor = rd.getAuthenticatedData();
        pretty = Util.cborPrettyPrint(Util.canonicalizeCbor(resultCbor));
        assertEquals("{\n"
                        + "  'org.iso.18013-5.2019' : {\n"
                        + "    'Height' : 180,\n"
                        + "    'Last name' : 'Turing',\n"
                        + "    'Birth date' : '19120623',\n"
                        + "    'First name' : 'Alan',\n"
                        + "    'Cryptanalyst' : true,\n"
                        + "    'Home address' : 'Maida Vale, London, England',\n"
                        + "    'Portrait image' : [0x01, 0x02]\n"
                        + "  }\n"
                        + "}",
                pretty);

        byte[] deviceAuthentication = Util.cborEncode(new CborBuilder()
                .addArray()
                .add("DeviceAuthentication")
                .add(Util.cborDecode(sessionTranscript))
                .add("org.iso.18013-5.2019.mdl")
                .add(Util.cborBuildTaggedByteString(resultCbor))
                .end()
                .build().get(0));

        byte[] deviceAuthenticationBytes =
                Util.cborEncode(Util.cborBuildTaggedByteString((deviceAuthentication)));

        byte[] mac = rd.getMessageAuthenticationCode();
        byte[] signature = rd.getEcdsaSignature();
        assertTrue(mac != null || signature != null);
        if (mac != null) {
            // Calculate the MAC by deriving the key using ECDH and HKDF.
            SecretKey eMacKey = Util.calcEMacKeyForReader(
                    key0Cert.getPublicKey(),
                    readerEphemeralKeyPair.getPrivate(),
                    sessionTranscript);
            byte[] expectedMac = Util.cborEncode(Util.coseMac0(
                    eMacKey,
                    new byte[0],                  // payload
                    deviceAuthenticationBytes));  // detached content

            // Then compare it with what the TA produced.
            assertArrayEquals(expectedMac, mac);
        } else {
            assertTrue(Util.coseSign1CheckSignature(
                    Util.cborDecode(signature),
                    deviceAuthenticationBytes,
                    key0Cert.getPublicKey()));
        }

        // Check that key0's static auth data is returned and that this
        // key has an increased use-count.
        assertArrayEquals(new byte[]{42, 43, 44}, rd.getStaticAuthenticationData());
        assertArrayEquals(new int[]{1, 0, 0, 0, 0}, credential.getAuthenticationDataUsageCount());


        // Now do this one more time.... this time key4 should have been used. Check this by
        // inspecting use-counts and the static authentication data.
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new byte[]{43, 44, 45}, rd.getStaticAuthenticationData());
        assertArrayEquals(new int[]{1, 0, 0, 0, 1}, credential.getAuthenticationDataUsageCount());

        deviceAuthentication = Util.cborEncode(new CborBuilder()
                .addArray()
                .add("DeviceAuthentication")
                .add(Util.cborDecode(sessionTranscript))
                .add("org.iso.18013-5.2019.mdl")
                .add(Util.cborBuildTaggedByteString(resultCbor))
                .end()
                .build().get(0));

        deviceAuthenticationBytes =
                Util.cborEncode(Util.cborBuildTaggedByteString((deviceAuthentication)));

        // Verify Signature or MAC was made with key4.
        mac = rd.getMessageAuthenticationCode();
        signature = rd.getEcdsaSignature();
        assertTrue(mac != null || signature != null);
        if (mac != null) {
            SecretKey eMacKey = Util.calcEMacKeyForReader(
                    key4Cert.getPublicKey(),
                    readerEphemeralKeyPair.getPrivate(),
                    sessionTranscript);
            byte[] expectedMac = Util.cborEncode(Util.coseMac0(eMacKey,
                    new byte[0],                 // payload
                    deviceAuthenticationBytes));  // detached content
            assertArrayEquals(expectedMac, mac);
        } else {
            assertTrue(Util.coseSign1CheckSignature(
                    Util.cborDecode(signature),
                    deviceAuthenticationBytes,
                    key4Cert.getPublicKey()));
        }

        // And again.... this time key0 should have been used. Check it.
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new byte[]{42, 43, 44}, rd.getStaticAuthenticationData());
        assertArrayEquals(new int[]{2, 0, 0, 0, 1}, credential.getAuthenticationDataUsageCount());

        // And again.... this time key4 should have been used. Check it.
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new byte[]{43, 44, 45}, rd.getStaticAuthenticationData());
        assertArrayEquals(new int[]{2, 0, 0, 0, 2}, credential.getAuthenticationDataUsageCount());

        // We configured each key to have three uses only. So we have two more presentations
        // to go until we run out... first, check that only three keys need certifications
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(3, certificates.size());

        // Then exhaust the two we've already configured.
        for (int n = 0; n < 2; n++) {
            credential = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            ephemeralKeyPair = credential.createEphemeralKeyPair();
            credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
            sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
            credential.setSessionTranscript(sessionTranscript);
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
            assertNotNull(rd);
        }
        assertArrayEquals(new int[]{3, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());

        // Now we should have five certs needing certification.
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certificates.size());

        // We still have the two keys which have been exhausted.
        assertArrayEquals(new int[]{3, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());

        // Check that we fail when running out of presentations (and explicitly don't allow
        // exhausting keys).
        try {
            credential = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            ephemeralKeyPair = credential.createEphemeralKeyPair();
            credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
            sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
            credential.setAllowUsingExhaustedKeys(false);
            credential.setSessionTranscript(sessionTranscript);
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
            fail();
        } catch (IdentityCredentialException e) {
            assertTrue(e instanceof NoAuthenticationKeyAvailableException);
        }
        assertArrayEquals(new int[]{3, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());

        // Now try with allowing using auth keys already exhausted... this should work!
        try {
            credential = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            ephemeralKeyPair = credential.createEphemeralKeyPair();
            credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
            sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
            credential.setSessionTranscript(sessionTranscript);
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        assertArrayEquals(new int[]{4, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());

        // Check that replenishing works...
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certificates.size());
        X509Certificate keyNewCert = certificates.iterator().next();
        try {
            storeStaticAuthenticationData(credential, keyNewCert, new byte[]{10, 11, 12});
            certificates = credential.getAuthKeysNeedingCertification();
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        assertEquals(4, certificates.size());
        assertArrayEquals(new int[]{0, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new byte[]{10, 11, 12}, rd.getStaticAuthenticationData());
        assertArrayEquals(new int[]{1, 0, 0, 0, 3}, credential.getAuthenticationDataUsageCount());

        deviceAuthentication = Util.cborEncode(new CborBuilder()
                .addArray()
                .add("DeviceAuthentication")
                .add(Util.cborDecode(sessionTranscript))
                .add("org.iso.18013-5.2019.mdl")
                .add(Util.cborBuildTaggedByteString(resultCbor))
                .end()
                .build().get(0));

        deviceAuthenticationBytes =
                Util.cborEncode(Util.cborBuildTaggedByteString((deviceAuthentication)));

        mac = rd.getMessageAuthenticationCode();
        signature = rd.getEcdsaSignature();
        assertTrue(mac != null || signature != null);
        if (mac != null) {
            SecretKey eMacKey = Util.calcEMacKeyForReader(
                    keyNewCert.getPublicKey(),
                    readerEphemeralKeyPair.getPrivate(),
                    sessionTranscript);
            byte[] expectedMac = Util.cborEncode(Util.coseMac0(eMacKey,
                    new byte[0],                  // payload
                    deviceAuthenticationBytes));  // detached content
            assertArrayEquals(expectedMac, rd.getMessageAuthenticationCode());
        } else {
            assertTrue(Util.coseSign1CheckSignature(
                    Util.cborDecode(signature),
                    deviceAuthenticationBytes,
                    keyNewCert.getPublicKey()));
        }

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    // TODO: test storeStaticAuthenticationData() throwing UnknownAuthenticationKeyException
    // on an unknown auth key

    @SuppressWarnings("deprecation")
    @Test
    public void dynamicAuthWithExpirationTest() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202101);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);

        credential.setAvailableAuthenticationKeys(3, 5);

        Collection<X509Certificate> certificates = null;
        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(3, certificates.size());

        // Endorse an auth-key but set expiration to 10 seconds in the future.
        //
        Calendar now = Calendar.getInstance();
        Calendar tenSecondsFromNow = Calendar.getInstance();
        tenSecondsFromNow.add(Calendar.SECOND, 10);
        try {
            X509Certificate key0Cert = certificates.iterator().next();
            storeStaticAuthenticationData(credential, key0Cert,
                    tenSecondsFromNow,
                    new byte[]{52, 53, 44});
            certificates = credential.getAuthKeysNeedingCertification();
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        assertEquals(2, certificates.size());
        assertArrayEquals(
                new int[]{0, 0, 0},
                credential.getAuthenticationDataUsageCount());
        // Check that presentation works.
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 0, 0},
                credential.getAuthenticationDataUsageCount());

        SystemClock.sleep(11 * 1000);

        certificates = credential.getAuthKeysNeedingCertification();
        assertEquals(3, certificates.size());

        // Check that presentation now fails..
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
            fail();
        } catch (NoAuthenticationKeyAvailableException e) {
            // This is the expected path...
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 0, 0},
                credential.getAuthenticationDataUsageCount());

        // Check that it works if we use setAllowUsingExpiredKeys(true)
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            tc.setAllowUsingExpiredKeys(true);   // <-- this is the call that makes the difference!
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{2, 0, 0},
                credential.getAuthenticationDataUsageCount());

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    @Test
    public void dynamicAuthMinValidTimeTest() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202301);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);

        // Set minValidTime to five seconds...
        credential.setAvailableAuthenticationKeys(3, 5, 5000);

        Collection<X509Certificate> authKeys = null;
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(3, authKeys.size());

        // Endorse all auth-key but set expiration to 10 seconds in the future.
        //
        long beginTime = Calendar.getInstance().getTimeInMillis();
        long tenSecondsFromBeginning = beginTime + 10*1000;
        for (X509Certificate authKey : authKeys) {
            try {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(tenSecondsFromBeginning);
                storeStaticAuthenticationData(credential, authKey,
                        c,
                        new byte[]{52, 53, 44});
            } catch (IdentityCredentialException e) {
                e.printStackTrace();
                fail();
            }
        }
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(0, authKeys.size());
        assertArrayEquals(
                new int[]{0, 0, 0},
                credential.getAuthenticationDataUsageCount());
        // Check that presentation works.
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        // At this point, no authKeys are tagged as needing replacement
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 0, 0},
                credential.getAuthenticationDataUsageCount());
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(0, authKeys.size());

        // **Six** seconds later the AuthKeys are still usable but all three should be tagged as
        // needing replacement.
        SystemClock.sleep(6 * 1000);
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(3, authKeys.size());
        // However presentations should _still_ work since they haven't expired. Check this.
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 1, 0},
                credential.getAuthenticationDataUsageCount());
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(3, authKeys.size());

        // Now replace all AuthKeys, set expiration to 15 seconds from beginning
        long fifteenSecondsFromBeginning = beginTime + 15*1000;
        for (X509Certificate authKey : authKeys) {
            try {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(fifteenSecondsFromBeginning);
                storeStaticAuthenticationData(credential, authKey,
                        c,
                        new byte[]{52, 53, 44});
            } catch (IdentityCredentialException e) {
                e.printStackTrace();
                fail();
            }
        }
        // At this point (T + 6 sec), no authKeys are tagged as needing replacement
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(0, authKeys.size());
        assertArrayEquals(
                new int[]{0, 0, 0},
                credential.getAuthenticationDataUsageCount());

        // **Five** seconds after this (T + 11sec), these new authKeys should be tagged
        // as needing replacement but still work. Check this.
        SystemClock.sleep(5 * 1000);
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 0, 0},
                credential.getAuthenticationDataUsageCount());
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(3, authKeys.size());

        // **Five** seconds later (T + 16sec) all AuthKeys should be expired and still
        // tagged as needing replacement. Check this.
        SystemClock.sleep(5 * 1000);
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(3, authKeys.size());
        // Check that presentation now fails..
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
            fail();
        } catch (NoAuthenticationKeyAvailableException e) {
            // This is the expected path...
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 0, 0},
                credential.getAuthenticationDataUsageCount());

        // Check that it works if we use setAllowUsingExpiredKeys(true)
        try {
            IdentityCredential tc = store.getCredentialByName(credentialName,
                    IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
            tc.setAllowUsingExpiredKeys(true);   // <-- this is the call that makes the difference!
            KeyPair ekp = tc.createEphemeralKeyPair();
            KeyPair rekp = Util.createEphemeralKeyPair(EcCurve.P256);
            tc.setReaderEphemeralPublicKey(rekp.getPublic());
            tc.setSessionTranscript(Util.buildSessionTranscript(ekp));
            Map<String, Collection<String>> etr = new LinkedHashMap<>();
            etr.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
            ResultData rd = tc.getEntries(
                    Util.createItemsRequest(etr, null),
                    etr,
                    null);
        } catch (IdentityCredentialException e) {
            e.printStackTrace();
            fail();
        }
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertArrayEquals(
                new int[]{1, 1, 0},
                credential.getAuthenticationDataUsageCount());

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    @Test
    public void dynamicAuthCanGetExpirations() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202101);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);

        int numAuthKeys = 10;
        credential.setAvailableAuthenticationKeys(numAuthKeys, 1, 0);

        Collection<X509Certificate> authKeys = null;
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(numAuthKeys, authKeys.size());

        // Endorse all auth keys and set expiration times to known values in the future
        //
        long now = Calendar.getInstance().getTimeInMillis();
        long tenSecondsFromBeginning = now + 10*1000;
        int n = 0;
        for (X509Certificate authKey : authKeys) {
            try {
                Calendar expiration = Calendar.getInstance();
                expiration.setTimeInMillis(tenSecondsFromBeginning + n);
                // For the fourth certificate, don't set expiration
                if (n == 3) {
                    storeStaticAuthenticationData(credential, authKey,
                            new byte[]{52, 53, 44});
                } else {
                    storeStaticAuthenticationData(credential, authKey,
                            expiration,
                            new byte[]{52, 53, 44});
                }
            } catch (IdentityCredentialException e) {
                e.printStackTrace();
                fail();
            }
            n++;
        }
        authKeys = credential.getAuthKeysNeedingCertification();
        assertEquals(0, authKeys.size());

        // Check we can read back expirations
        List<Calendar> expirations = credential.getAuthenticationDataExpirations();
        assertEquals(expirations.size(), numAuthKeys);
        for (n = 0; n < numAuthKeys; n++) {
            Calendar expiration = expirations.get(n);
            if (n == 3) {
                assertNull(expiration);
            } else {
                assertEquals(tenSecondsFromBeginning + n, expiration.getTimeInMillis());
            }
        }

        // ... and we're done. Clean up after ourselves.
        store.deleteCredentialByName(credentialName);
    }

    @Test
    public void dynamicAuthMultipleGetEntries() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);
        assertArrayEquals(new int[0], credential.getAuthenticationDataUsageCount());

        credential.setAvailableAuthenticationKeys(5, 3);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0},
                credential.getAuthenticationDataUsageCount());

        Collection<X509Certificate> certs = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certs.size());

        // Certify authKeys 0 and 1
        //
        X509Certificate key0Cert = new ArrayList<X509Certificate>(certs).get(0);
        storeStaticAuthenticationData(credential, key0Cert, new byte[]{42, 43, 44});
        X509Certificate key1Cert = new ArrayList<X509Certificate>(certs).get(1);
        storeStaticAuthenticationData(credential, key1Cert, new byte[]{43, 44, 45});

        // Get ready to present...
        //
        Map<String, Collection<String>> entriesToRequest = new LinkedHashMap<>();
        entriesToRequest.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
        KeyPair ephemeralKeyPair = credential.createEphemeralKeyPair();
        KeyPair readerEphemeralKeyPair = Util.createEphemeralKeyPair(EcCurve.P256);
        byte[] sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        credential.setSessionTranscript(sessionTranscript);

        // First getEntries() call should pick key0
        //
        ResultData rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new int[]{1, 0, 0, 0, 0}, credential.getAuthenticationDataUsageCount());
        assertArrayEquals(new byte[]{42, 43, 44}, rd.getStaticAuthenticationData());

        // Doing another getEntries() call on the same object should use the same key. Check this
        // by inspecting use-counts and the static authentication data.
        //
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new int[]{1, 0, 0, 0, 0}, credential.getAuthenticationDataUsageCount());
        assertArrayEquals(new byte[]{42, 43, 44}, rd.getStaticAuthenticationData());
    }

    @Test
    public void dynamicAuthNoUsageCountIncrement() throws Exception {
        Context appContext = androidx.test.InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Utility.getIdentityCredentialStore(appContext);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202201);

        String credentialName = "test";

        store.deleteCredentialByName(credentialName);
        Collection<X509Certificate> certChain = ProvisioningTest.createCredential(store,
                credentialName);

        IdentityCredential credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        assertNotNull(credential);
        assertArrayEquals(new int[0], credential.getAuthenticationDataUsageCount());

        credential.setAvailableAuthenticationKeys(5, 3);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0},
                credential.getAuthenticationDataUsageCount());

        Collection<X509Certificate> certs = credential.getAuthKeysNeedingCertification();
        assertEquals(5, certs.size());

        // Certify authKeys 0 and 1
        //
        X509Certificate key0Cert = new ArrayList<X509Certificate>(certs).get(0);
        storeStaticAuthenticationData(credential, key0Cert, new byte[]{42, 43, 44});
        X509Certificate key1Cert = new ArrayList<X509Certificate>(certs).get(1);
        storeStaticAuthenticationData(credential, key1Cert, new byte[]{43, 44, 45});

        // Get ready to present...
        //
        Map<String, Collection<String>> entriesToRequest = new LinkedHashMap<>();
        entriesToRequest.put("org.iso.18013-5.2019", Arrays.asList("First name", "Last name"));
        KeyPair ephemeralKeyPair = credential.createEphemeralKeyPair();
        KeyPair readerEphemeralKeyPair = Util.createEphemeralKeyPair(EcCurve.P256);
        byte[] sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        credential.setSessionTranscript(sessionTranscript);

        // First getEntries() call should pick key0
        //
        ResultData rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new int[]{1, 0, 0, 0, 0}, credential.getAuthenticationDataUsageCount());
        assertArrayEquals(new byte[]{42, 43, 44}, rd.getStaticAuthenticationData());

        // Now configure to not increase the usage count... we should be able to do as many
        // presentations as we want and usage counts shouldn't change. We'll just do 3.
        //
        // Note that key1 will be picked (we can check that by inspecting static authentication
        // data) but its usage count won't be increased.
        //
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        readerEphemeralKeyPair = Util.createEphemeralKeyPair(EcCurve.P256);
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        credential.setSessionTranscript(sessionTranscript);
        credential.setIncrementKeyUsageCount(false);
        for (int n = 0; n < 3; n++) {
            rd = credential.getEntries(
                    Util.createItemsRequest(entriesToRequest, null),
                    entriesToRequest,
                    null);
            assertArrayEquals(new int[]{1, 0, 0, 0, 0},
                    credential.getAuthenticationDataUsageCount());
            assertArrayEquals(new byte[]{43, 44, 45}, rd.getStaticAuthenticationData());
        }

        // With usage counts incrementing again, do another getEntries() call. It should pick key1
        // and use it up. Check this by inspecting use-counts and the static authentication data.
        //
        credential = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);
        ephemeralKeyPair = credential.createEphemeralKeyPair();
        readerEphemeralKeyPair = Util.createEphemeralKeyPair(EcCurve.P256);
        sessionTranscript = Util.buildSessionTranscript(ephemeralKeyPair);
        credential.setReaderEphemeralPublicKey(readerEphemeralKeyPair.getPublic());
        credential.setSessionTranscript(sessionTranscript);
        rd = credential.getEntries(
                Util.createItemsRequest(entriesToRequest, null),
                entriesToRequest,
                null);
        assertArrayEquals(new int[]{1, 1, 0, 0, 0}, credential.getAuthenticationDataUsageCount());
        assertArrayEquals(new byte[]{43, 44, 45}, rd.getStaticAuthenticationData());
    }

    private void storeStaticAuthenticationData(IdentityCredential credential, X509Certificate certificate, byte[] staticAuthData)
        throws UnknownAuthenticationKeyException {
        if (useNewStoreStaticAuthDataMethod) {
            credential.storeStaticAuthenticationData(certificate.getPublicKey(), staticAuthData);
        } else {
            credential.storeStaticAuthenticationData(certificate, staticAuthData);
        }
    }

    private void storeStaticAuthenticationData(IdentityCredential credential, X509Certificate certificate, Calendar expirationDate, byte[] staticAuthData)
        throws UnknownAuthenticationKeyException {
        if (useNewStoreStaticAuthDataMethod) {
            credential.storeStaticAuthenticationData(certificate.getPublicKey(), expirationDate, staticAuthData);
        } else {
            credential.storeStaticAuthenticationData(certificate, expirationDate, staticAuthData);
        }
    }
}
