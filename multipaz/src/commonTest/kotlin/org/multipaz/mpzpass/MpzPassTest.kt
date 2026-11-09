package org.multipaz.mpzpass

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.DocumentStore
import org.multipaz.document.ImportMpzPassException
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.IntegerOption
import org.multipaz.documenttype.MdocPortraitEquivalenceData
import org.multipaz.documenttype.StringOption
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.util.inflate
import kotlin.experimental.xor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpzPassTest {

    @Test
    fun testUserAuthenticationRequiredExportImport() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(userAuthenticationRequired = true),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass(
            SoftwareKeyUnlockData(
                secureArea = harness.softwareSecureArea,
                alias = credential.alias,
                userAuthenticated = true
            )
        )
        assertTrue(pass.userAuthenticationRequired)
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        val importedCredential = importedDoc.getCredentials().first() as MdocCredential
        val keyInfo = harness.softwareSecureArea.getKeyInfo(importedCredential.alias)
        assertTrue(keyInfo.isUserAuthenticationRequired)
    }

    @Test
    fun testIsoMdocExportImport() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
        assertEquals(importedDoc.mpzPassVersion, pass.version)
        assertNotEquals(doc.identifier, importedDoc.identifier)
        assertNotEquals(doc.created, importedDoc.created)
        assertEquals(doc.displayName, importedDoc.displayName)
        assertEquals(doc.typeDisplayName, importedDoc.typeDisplayName)
        assertEquals(doc.cardArt, importedDoc.cardArt)
        assertEquals(doc.provisioned, importedDoc.provisioned)
        assertEquals(1, importedDoc.getCredentials().size)

        val importedCredential = importedDoc.getCredentials().first()
        assertNotEquals(credential.identifier, importedCredential.identifier)
        assertEquals(credential::class, importedCredential::class)
        assertEquals(credential.credentialType, importedCredential.credentialType)
        assertEquals(credential.issuerProvidedData, importedCredential.issuerProvidedData)
        importedCredential as MdocCredential
        assertNotEquals(credential.alias, importedCredential.alias)
        assertEquals(credential.secureArea, importedCredential.secureArea)
    }

    @Test
    fun testKeyBoundSdJwtExportImport() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "EU PID specimen",
            typeDisplayName = "EU PID",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "sdjwt",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
        assertEquals(importedDoc.mpzPassVersion, pass.version)
        assertNotEquals(doc.identifier, importedDoc.identifier)
        assertNotEquals(doc.created, importedDoc.created)
        assertEquals(doc.displayName, importedDoc.displayName)
        assertEquals(doc.typeDisplayName, importedDoc.typeDisplayName)
        assertEquals(doc.cardArt, importedDoc.cardArt)
        assertEquals(doc.provisioned, importedDoc.provisioned)
        assertEquals(1, importedDoc.getCredentials().size)

        val importedCredential = importedDoc.getCredentials().first()
        assertNotEquals(credential.identifier, importedCredential.identifier)
        assertEquals(credential::class, importedCredential::class)
        assertEquals(credential.credentialType, importedCredential.credentialType)
        assertEquals(credential.issuerProvidedData, importedCredential.issuerProvidedData)
        importedCredential as SecureAreaBoundCredential
        assertNotEquals(credential.alias, importedCredential.alias)
        assertEquals(credential.secureArea, importedCredential.secureArea)
    }

    @Test
    fun testKeylessSdJwtExportImport() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Back to Utopia",
            typeDisplayName = "Utopia movie ticket",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = UtopiaMovieTicket.getDocumentType().createKeylessSdJwtVcCredentialWithSampleData(
            document = doc,
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "sdjwt",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
        assertEquals(importedDoc.mpzPassVersion, pass.version)
        assertNotEquals(doc.identifier, importedDoc.identifier)
        assertNotEquals(doc.created, importedDoc.created)
        assertEquals(doc.displayName, importedDoc.displayName)
        assertEquals(doc.typeDisplayName, importedDoc.typeDisplayName)
        assertEquals(doc.cardArt, importedDoc.cardArt)
        assertEquals(doc.provisioned, importedDoc.provisioned)
        assertEquals(1, importedDoc.getCredentials().size)
        val importedCredential = importedDoc.getCredentials().first()
        assertNotEquals(credential.identifier, importedCredential.identifier)
        assertEquals(credential::class, importedCredential::class)
        assertEquals(credential.credentialType, importedCredential.credentialType)
        assertEquals(credential.issuerProvidedData, importedCredential.issuerProvidedData)
    }

    @Test
    fun passWithUpdate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
        assertEquals(importedDoc.mpzPassVersion, pass.version)
        assertNotEquals(doc.identifier, importedDoc.identifier)
        assertNotEquals(doc.created, importedDoc.created)
        assertEquals(doc.displayName, importedDoc.displayName)
        assertEquals(doc.typeDisplayName, importedDoc.typeDisplayName)
        assertEquals(doc.cardArt, importedDoc.cardArt)
        assertEquals(doc.provisioned, importedDoc.provisioned)
        assertEquals(1, importedDoc.getCredentials().size)
        val importedCredential = importedDoc.getCredentials().first()
        assertNotEquals(credential.identifier, importedCredential.identifier)
        assertEquals(credential::class, importedCredential::class)
        assertEquals(credential.credentialType, importedCredential.credentialType)
        assertEquals(credential.issuerProvidedData, importedCredential.issuerProvidedData)
        importedCredential as MdocCredential
        assertNotEquals(credential.alias, importedCredential.alias)
        assertEquals(credential.secureArea, importedCredential.secureArea)

        val updatedPass = pass.copy(
            version = pass.version + 1
        )
        val updatedDoc = harness.documentStore.importMpzPass(
            mpzPass = updatedPass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(updatedDoc, importedDoc)
        assertEquals(updatedDoc.mpzPassId, updatedPass.uniqueId)
        assertEquals(updatedDoc.mpzPassVersion, updatedPass.version)

        val updatedPassSameVersion = updatedPass.copy(
            version = pass.version + 1
        )
        // Check same version is rejected.
        assertFailsWith(
            exceptionClass = ImportMpzPassException::class,
            message = "Pass already imported at version 1 which is greater or equal to version 1"
        ) {
            harness.documentStore.importMpzPass(
                mpzPass = updatedPassSameVersion,
                isoMdocDomain = "mdoc",
                sdJwtVcDomain = "sdjwt",
                keylessSdJwtVcDomain = "sdjwt-keyless"
            )
        }
        // Check older versions are rejected.
        assertFailsWith(
            exceptionClass = ImportMpzPassException::class,
            message = "Pass already imported at version 1 which is greater or equal to version 0"
        ) {
            harness.documentStore.importMpzPass(
                mpzPass = pass,
                isoMdocDomain = "mdoc",
                sdJwtVcDomain = "sdjwt",
                keylessSdJwtVcDomain = "sdjwt-keyless"
            )
        }
    }

    @Test
    fun testReaderIdentifiersExportImportIsoMdoc() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val readerIds = listOf(ByteString(0x11, 0x22, 0x33), ByteString(0x44, 0x55, 0x66))
        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
            readerIdentifiers = readerIds,
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(readerIds, pass.readerIdentifiers)
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(readerIds, importedDoc.readerIdentifiers)
    }

    @Test
    fun testReaderIdentifiersExportImportKeyBoundSdJwt() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val readerIds = listOf(ByteString(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()))
        val doc = harness.documentStore.createDocument(
            displayName = "EU PID specimen",
            typeDisplayName = "EU PID",
            cardArt = ByteString(1, 2, 3),
            readerIdentifiers = readerIds,
        )
        val credential = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "sdjwt",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(readerIds, pass.readerIdentifiers)
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(readerIds, importedDoc.readerIdentifiers)
    }

    @Test
    fun testReaderIdentifiersExportImportKeylessSdJwt() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val readerIds = listOf(ByteString(0x12, 0x34))
        val doc = harness.documentStore.createDocument(
            displayName = "Back to Utopia",
            typeDisplayName = "Utopia movie ticket",
            cardArt = ByteString(1, 2, 3),
            readerIdentifiers = readerIds,
        )
        val credential = UtopiaMovieTicket.getDocumentType().createKeylessSdJwtVcCredentialWithSampleData(
            document = doc,
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "sdjwt",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertEquals(readerIds, pass.readerIdentifiers)
        assertEquals(
            pass,
            MpzPass.fromDataItem(pass.toDataItem())
        )

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(readerIds, importedDoc.readerIdentifiers)
    }

    @Test
    fun testPassUpdateWithReaderIdentifiers() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val readerIdsV0 = listOf(ByteString(0x01, 0x02))
        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
            readerIdentifiers = readerIdsV0,
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val passV0 = credential.exportToMpzPass()
        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = passV0,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(readerIdsV0, importedDoc.readerIdentifiers)

        // Update pass to version 1 with different reader identifiers
        val readerIdsV1 = listOf(ByteString(0x03, 0x04), ByteString(0x05, 0x06))
        val passV1 = passV0.copy(
            version = 1,
            readerIdentifiers = readerIdsV1
        )
        val updatedDoc1 = harness.documentStore.importMpzPass(
            mpzPass = passV1,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(updatedDoc1, importedDoc)
        assertEquals(readerIdsV1, updatedDoc1.readerIdentifiers)

        // Update pass to version 2 with empty reader identifiers
        val passV2 = passV1.copy(
            version = 2,
            readerIdentifiers = emptyList()
        )
        val updatedDoc2 = harness.documentStore.importMpzPass(
            mpzPass = passV2,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(updatedDoc2, importedDoc)
        assertEquals(emptyList(), updatedDoc2.readerIdentifiers)
    }

    @Test
    fun testSignedPassIsoMdoc() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()
        assertFalse(pass.isSigned)
        assertNull(pass.issuerCertificateChain)

        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = X509Cert.Builder(
            publicKey = issuerPrivateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            issuer = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ).build()
        val certChain = X509CertChain(listOf(issuerCert))

        val signedDataItem = pass.toDataItem(
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            issuerCertificateChain = certChain
        )
        val decodedPass = MpzPass.fromDataItem(signedDataItem)
        assertTrue(decodedPass.isSigned)
        assertEquals(certChain, decodedPass.issuerCertificateChain)
        assertEquals(pass.uniqueId, decodedPass.uniqueId)
        assertEquals(pass.version, decodedPass.version)
        assertEquals(pass.name, decodedPass.name)

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = decodedPass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
    }

    @Test
    fun testSignedPassSdJwtVc() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "EU PID specimen",
            typeDisplayName = "EU PID",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "sdjwt",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()

        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = X509Cert.Builder(
            publicKey = issuerPrivateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            issuer = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ).build()
        val certChain = X509CertChain(listOf(issuerCert))

        val signedDataItem = pass.toDataItem(
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            issuerCertificateChain = certChain
        )
        val decodedPass = MpzPass.fromDataItem(signedDataItem)
        assertTrue(decodedPass.isSigned)
        assertEquals(certChain, decodedPass.issuerCertificateChain)
        assertEquals(pass.uniqueId, decodedPass.uniqueId)
        assertEquals(pass.version, decodedPass.version)
        assertEquals(pass.name, decodedPass.name)

        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = decodedPass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )
        assertEquals(importedDoc.mpzPassId, pass.uniqueId)
    }

    @Test
    fun testSignedPassTamperingFails() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()

        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = X509Cert.Builder(
            publicKey = issuerPrivateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            issuer = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ).build()
        val certChain = X509CertChain(listOf(issuerCert))

        val signedDataItem = pass.toDataItem(
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            issuerCertificateChain = certChain
        )

        // Modify the payload bytes inside the COSE_Sign1 structure
        val tagged = signedDataItem.asArray[1] as Tagged
        val cose = CoseSign1.fromDataItem(tagged.taggedItem)
        val tamperedPayload = cose.payload!!.copyOf()
        tamperedPayload[tamperedPayload.size - 1] = (tamperedPayload[tamperedPayload.size - 1] xor 0xFF.toByte())
        val tamperedCose = cose.copy(payload = tamperedPayload)
        val tamperedDataItem = buildCborArray {
            add("MpzPass")
            add(Tagged(Tagged.COSE_SIGN1, tamperedCose.toDataItem()))
        }

        assertFailsWith<SignatureVerificationException> {
            MpzPass.fromDataItem(tamperedDataItem)
        }
    }

    @Test
    fun testSignedPassWrongKeyFails() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()

        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val wrongCert = X509Cert.Builder(
            publicKey = otherKey.publicKey,
            signingKey = AsymmetricKey.anonymous(otherKey),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Wrong Issuer"),
            issuer = X500Name.fromName("CN=Wrong Issuer"),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ).build()
        val wrongCertChain = X509CertChain(listOf(wrongCert))

        // Sign with issuerPrivateKey, but put wrongCertChain in x5chain
        val signedDataItem = pass.toDataItem(
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            issuerCertificateChain = wrongCertChain
        )

        assertFailsWith<SignatureVerificationException> {
            MpzPass.fromDataItem(signedDataItem)
        }

        // Bypassing signature verification succeeds
        val decoded = MpzPass.fromDataItem(signedDataItem, disableSignatureVerification = true)
        assertEquals(pass.uniqueId, decoded.uniqueId)
        assertTrue(decoded.isSigned)
    }

    @Test
    fun testSignedPassUnprotectedHeaderRejected() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val pass = credential.exportToMpzPass()

        val issuerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerCert = X509Cert.Builder(
            publicKey = issuerPrivateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            issuer = X500Name.fromName("CN=Multipaz Test Pass Issuer"),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ).build()
        val certChain = X509CertChain(listOf(issuerCert))

        val unsignedDataItem = pass.toDataItem()
        val compressedBytes = unsignedDataItem.asArray[1].asBstr

        // Sign with x5chain in unprotected header instead of protected header
        val coseWithUnprotected = Cose.coseSign1Sign(
            signingKey = AsymmetricKey.anonymous(issuerPrivateKey),
            message = compressedBytes,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                    issuerPrivateKey.curve.defaultSigningAlgorithmFullySpecified.coseAlgorithmIdentifier!!.toDataItem()
            ),
            unprotectedHeaders = mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                    certChain.toDataItem()
            )
        )
        val dataItem = buildCborArray {
            add("MpzPass")
            add(Tagged(Tagged.COSE_SIGN1, coseWithUnprotected.toDataItem()))
        }

        assertFailsWith<IllegalArgumentException> {
            MpzPass.fromDataItem(dataItem)
        }
    }

    @Test
    fun testShareablePass() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val basePass = credential.exportToMpzPass()
        assertFalse(basePass.shareable)

        // Test pass with shareable = true
        val shareablePass = basePass.copy(shareable = true)
        val shareableDataItem = shareablePass.toDataItem()
        val decodedShareablePass = MpzPass.fromDataItem(shareableDataItem)
        assertTrue(decodedShareablePass.shareable)

        // Verify the CBOR encoding actually has "shareable": true
        val decompressedBytes = shareableDataItem.asArray[1].asBstr.inflate()
        val decodedMap = Cbor.decode(decompressedBytes)
        assertEquals(true, decodedMap["shareable"].asBoolean)

        // Test pass with shareable = false
        val nonShareablePass = basePass.copy(shareable = false)
        val nonShareableDataItem = nonShareablePass.toDataItem()
        val decodedNonShareablePass = MpzPass.fromDataItem(nonShareableDataItem)
        assertFalse(decodedNonShareablePass.shareable)

        // Verify "shareable" key is omitted when false
        val nonShareableBytes = nonShareableDataItem.asArray[1].asBstr.inflate()
        val nonShareableMap = Cbor.decode(nonShareableBytes)
        assertNull(nonShareableMap.getOrNull("shareable"))
    }

    @Test
    fun testClaimMetadataRoundTripAndLookup() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()

        val doc = harness.documentStore.createDocument(
            displayName = "Driving license specimen",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(1, 2, 3),
        )
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = doc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            domain = "mdoc",
        )
        doc.edit { provisioned = true }

        val basePass = credential.exportToMpzPass()
        assertNull(basePass.metadata)
        assertNull(MpzPass.fromDataItem(basePass.toDataItem()).metadata)

        val nameGroup = MpzPassGroupMetadata(
            displayName = mapOf("en-US" to "Name", "ja-JP" to "氏名"),
            description = mapOf("en-US" to "Full name", "ja-JP" to "フルネーム"),
            icon = Icon.PERSON,
            sensitivity = DocumentAttributeSensitivity.PII,
            minClaimsToGroup = 2
        )
        val addressGroup = MpzPassGroupMetadata(
            displayName = mapOf("en-US" to "Address"),
            icon = Icon.PLACE,
            minClaimsToGroup = 1
        )
        val familyNameMetadata = MpzPassClaimMetadata(
            displayName = mapOf("en-US" to "Family Name", "ja-JP" to "氏"),
            description = mapOf("en-US" to "Last name or surname", "ja-JP" to "姓"),
            icon = Icon.PERSON,
            sensitivity = DocumentAttributeSensitivity.PII,
            groupId = "name"
        )
        val ageOver18Metadata = MpzPassClaimMetadata(
            displayName = mapOf("en" to "Older Than 18", "da" to "Over 18 år"),
            description = null,
            type = DocumentAttributeType.Boolean,
            icon = null,
            sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION
        )
        val streetAddressMetadata = MpzPassClaimMetadata(
            displayName = mapOf("en-US" to "Street Address"),
            description = mapOf("en-US" to "Street and house number"),
            icon = Icon.HOUSE,
            groupId = "address"
        )

        val givenNamePath = buildJsonArray { add("given_name") }
        val streetAddressPath = buildJsonArray {
            add("address")
            add("street_address")
        }

        val portraitEquivalenceData = MdocPortraitEquivalenceData(
            namespace = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "portrait"
        )

        val metadata = MpzPassMetadata(
            claimGroupsMetadata = mapOf(
                "name" to nameGroup,
                "address" to addressGroup
            ),
            isoMdocClaimsMetadata = mapOf(
                DrivingLicense.MDL_NAMESPACE to mapOf(
                    "family_name" to familyNameMetadata,
                    "age_over_18" to ageOver18Metadata
                )
            ),
            isoMdocPortraitEquivalenceData = portraitEquivalenceData,
            sdJwtVcClaimsMetadata = mapOf(
                givenNamePath to MpzPassClaimMetadata(
                    displayName = mapOf("en" to "Given Name", "ja" to "名"),
                    icon = Icon.PERSON,
                    groupId = "name"
                ),
                streetAddressPath to streetAddressMetadata
            )
        )

        // Verify standalone MpzPassGroupMetadata and MpzPassMetadata CBOR round-trip
        assertEquals(nameGroup, MpzPassGroupMetadata.fromCbor(nameGroup.toCbor()))
        assertEquals(addressGroup, MpzPassGroupMetadata.fromCbor(addressGroup.toCbor()))
        assertEquals(metadata, MpzPassMetadata.fromCbor(metadata.toCbor()))

        val passWithClaims = basePass.copy(metadata = metadata)

        val encoded = passWithClaims.toDataItem()
        val decoded = MpzPass.fromDataItem(encoded)
        assertEquals(passWithClaims, decoded)
        assertEquals(portraitEquivalenceData, decoded.metadata!!.isoMdocPortraitEquivalenceData)
        assertEquals(nameGroup, decoded.metadata!!.getClaimGroupMetadata("name"))
        assertEquals(addressGroup, decoded.metadata!!.getClaimGroupMetadata("address"))
        assertNull(decoded.metadata!!.getClaimGroupMetadata("non_existent"))
        assertEquals("Name", nameGroup.getDisplayName(listOf("en-US")))
        assertEquals("氏名", nameGroup.getDisplayName(listOf("ja")))
        assertEquals("Full name", nameGroup.getDescription(listOf("en")))
        assertEquals("フルネーム", nameGroup.getDescription(listOf("ja-JP")))

        // Check ISO mdoc lookups
        val lookedUpFamilyName = decoded.getIsoMdocClaimMetadata(DrivingLicense.MDL_NAMESPACE, "family_name")
        assertEquals(familyNameMetadata, lookedUpFamilyName)
        assertEquals("Family Name", lookedUpFamilyName!!.getDisplayName(listOf("en-US")))
        assertEquals("氏", lookedUpFamilyName.getDisplayName(listOf("ja")))
        assertEquals("Last name or surname", lookedUpFamilyName.getDescription(listOf("en")))
        assertEquals("姓", lookedUpFamilyName.getDescription(listOf("ja-JP")))
        assertEquals(DocumentAttributeType.String, lookedUpFamilyName.type)
        assertEquals(Icon.PERSON, lookedUpFamilyName.icon)
        assertEquals(DocumentAttributeSensitivity.PII, lookedUpFamilyName.sensitivity)
        assertEquals("name", lookedUpFamilyName.groupId)

        val lookedUpAge = decoded.getIsoMdocClaimMetadata(DrivingLicense.MDL_NAMESPACE, "age_over_18")
        assertEquals(ageOver18Metadata, lookedUpAge)
        assertEquals("Over 18 år", lookedUpAge!!.getDisplayName(listOf("da-DK")))
        assertNull(lookedUpAge.getDescription(listOf("en")))
        assertEquals(DocumentAttributeType.Boolean, lookedUpAge.type)
        assertNull(lookedUpAge.icon)
        assertEquals(DocumentAttributeSensitivity.AGE_INFORMATION, lookedUpAge.sensitivity)
        assertNull(lookedUpAge.groupId)

        assertNull(decoded.getIsoMdocClaimMetadata(DrivingLicense.MDL_NAMESPACE, "non_existent"))
        assertNull(decoded.getIsoMdocClaimMetadata("org.example.unknown", "family_name"))

        // Check SD-JWT VC lookups
        val lookedUpStreet = decoded.getSdJwtVcClaimMetadata(streetAddressPath)
        assertEquals(streetAddressMetadata, lookedUpStreet)
        assertEquals("Street Address", lookedUpStreet!!.getDisplayName(listOf("en")))
        assertEquals("Street and house number", lookedUpStreet.getDescription(listOf("en")))
        assertEquals(Icon.HOUSE, lookedUpStreet.icon)
        assertEquals(DocumentAttributeSensitivity.PII, lookedUpStreet.sensitivity)
        assertEquals("address", lookedUpStreet.groupId)

        assertNull(decoded.getSdJwtVcClaimMetadata(buildJsonArray { add("unknown_claim") }))

        // Verify metadata fields are on CredentialData and defaults (sensitivity=PII, minClaimsToGroup=2) are omitted
        val decompressedBytes = encoded.asArray[1].asBstr.inflate()
        val credentialDataMap = Cbor.decode(decompressedBytes)
        val groupsMap = credentialDataMap["claimGroupsMetadata"]
        assertNull(groupsMap["name"].getOrNull("sensitivity"))
        assertNull(groupsMap["name"].getOrNull("minClaimsToGroup"))
        assertEquals("person", groupsMap["name"]["icon"]["m"].asTstr)
        assertEquals(1L, groupsMap["address"]["minClaimsToGroup"].asNumber)
        val mdocClaimsMap = credentialDataMap["isoMdocClaimsMetadata"][DrivingLicense.MDL_NAMESPACE]
        assertNull(mdocClaimsMap["family_name"].getOrNull("type"))
        assertNull(mdocClaimsMap["family_name"].getOrNull("sensitivity"))
        assertEquals("person", mdocClaimsMap["family_name"]["icon"]["m"].asTstr)
        assertEquals("name", mdocClaimsMap["family_name"]["groupId"].asTstr)
        assertEquals("BOOLEAN", mdocClaimsMap["age_over_18"]["type"].asTstr)
        assertEquals("AGE_INFORMATION", mdocClaimsMap["age_over_18"]["sensitivity"].asTstr)
        assertNull(mdocClaimsMap["age_over_18"].getOrNull("groupId"))
        val peMap = credentialDataMap["isoMdocPortraitEquivalenceData"]
        assertEquals(DrivingLicense.MDL_NAMESPACE, peMap["namespace"].asTstr)
        assertEquals("portrait", peMap["dataElementName"].asTstr)

        // Verify DocumentStore.importMpzPass persists mpzPassMetadata on Document
        harness.documentStore.deleteDocument(doc.identifier)
        val importedDoc = harness.documentStore.importMpzPass(
            mpzPass = decoded,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt_kb",
            keylessSdJwtVcDomain = "sdjwt_keyless"
        )
        assertEquals(metadata, importedDoc.mpzPassMetadata)

        // Verify mpzPassMetadata survives Document.edit, tags.edit, and store reload
        importedDoc.edit { displayName = "Updated DL" }
        importedDoc.tags.edit { set("tag1", ByteString(1)) }
        assertEquals(metadata, importedDoc.mpzPassMetadata)

        val reloadedStore = DocumentStore.Builder(
            storage = harness.storage,
            secureAreaRepository = harness.secureAreaRepository
        ).build()
        val reloadedDoc = reloadedStore.lookupDocument(importedDoc.identifier)!!
        assertEquals(metadata, reloadedDoc.mpzPassMetadata)

        // Re-exporting from credential preserves mpzPassMetadata
        val reexportedPass = (reloadedDoc.getCredentials().first() as MdocCredential).exportToMpzPass()
        assertEquals(metadata, reexportedPass.metadata)

        // Importing an updated version updates mpzPassMetadata
        val updatedMetadata = MpzPassMetadata(
            claimGroupsMetadata = mapOf("name" to nameGroup),
            isoMdocClaimsMetadata = mapOf(
                DrivingLicense.MDL_NAMESPACE to mapOf(
                    "family_name" to familyNameMetadata
                )
            )
        )
        val updatedPass = decoded.copy(
            version = decoded.version + 1,
            metadata = updatedMetadata
        )
        val updatedDoc = harness.documentStore.importMpzPass(
            mpzPass = updatedPass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt_kb",
            keylessSdJwtVcDomain = "sdjwt_keyless"
        )
        assertEquals(updatedMetadata, updatedDoc.mpzPassMetadata)
    }

    @Test
    fun testClaimMetadataUnknownValuesAndValidation() {
        // Unknown icon falls back to null; unknown type falls back to String; unknown sensitivity falls back to PII
        val rawCbor = buildCborMap {
            putCborMap("displayName") {
                put("en", "Test Claim")
            }
            put("type", "FUTURE_CLAIM_TYPE")
            putCborMap("icon") {
                put("m", "future_unknown_icon")
            }
            put("sensitivity", "FUTURE_SENSITIVITY_LEVEL")
        }
        val parsed = MpzPassClaimMetadata.fromDataItem(rawCbor)
        assertEquals(mapOf("en" to "Test Claim"), parsed.displayName)
        assertNull(parsed.description)
        assertEquals(DocumentAttributeType.String, parsed.type)
        assertNull(parsed.icon)
        assertEquals(DocumentAttributeSensitivity.PII, parsed.sensitivity)
        assertNull(parsed.groupId)

        // All 8 base claim types round-trip cleanly
        val allTypes = listOf(
            DocumentAttributeType.Blob to "BLOB",
            DocumentAttributeType.String to null,
            DocumentAttributeType.Number to "NUMBER",
            DocumentAttributeType.Date to "DATE",
            DocumentAttributeType.DateTime to "DATETIME",
            DocumentAttributeType.Picture to "PICTURE",
            DocumentAttributeType.Boolean to "BOOLEAN",
            DocumentAttributeType.ComplexType to "COMPLEX_TYPE"
        )
        for ((attrType, expectedCborStr) in allTypes) {
            val claimMeta = MpzPassClaimMetadata(
                displayName = mapOf("en" to "Claim"),
                type = attrType
            )
            val dataItem = claimMeta.toDataItem()
            assertEquals(expectedCborStr, dataItem.getOrNull("type")?.asTstr)
            assertEquals(claimMeta, MpzPassClaimMetadata.fromDataItem(dataItem))
        }

        // StringOptions and IntegerOptions are rejected in MpzPassClaimMetadata
        assertFailsWith<IllegalArgumentException> {
            MpzPassClaimMetadata(
                displayName = mapOf("en" to "Tier"),
                type = DocumentAttributeType.StringOptions(listOf(StringOption("gold", "Gold")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassClaimMetadata(
                displayName = mapOf("en" to "Sex"),
                type = DocumentAttributeType.IntegerOptions(listOf(IntegerOption(1, "Male")))
            )
        }

        val parsedGroup = MpzPassGroupMetadata.fromDataItem(rawCbor)
        assertEquals(mapOf("en" to "Test Claim"), parsedGroup.displayName)
        assertNull(parsedGroup.description)
        assertNull(parsedGroup.icon)
        assertEquals(DocumentAttributeSensitivity.PII, parsedGroup.sensitivity)
        assertEquals(2, parsedGroup.minClaimsToGroup)

        // Future icon format without "m" key falls back to null icon cleanly
        val futureIconCbor = buildCborMap {
            putCborMap("displayName") {
                put("en", "Test Claim")
            }
            putCborMap("icon") {
                put("svg", "<svg/>")
            }
        }
        assertNull(MpzPassClaimMetadata.fromDataItem(futureIconCbor).icon)
        assertNull(MpzPassGroupMetadata.fromDataItem(futureIconCbor).icon)

        // Empty icon map is rejected
        val emptyIconCbor = buildCborMap {
            putCborMap("displayName") {
                put("en", "Test Claim")
            }
            putCborMap("icon") {}
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassClaimMetadata.fromDataItem(emptyIconCbor)
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassGroupMetadata.fromDataItem(emptyIconCbor)
        }

        // Empty displayName map is rejected
        assertFailsWith<IllegalArgumentException> {
            MpzPassClaimMetadata(displayName = emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassGroupMetadata(displayName = emptyMap())
        }

        // Empty non-null description map is rejected
        assertFailsWith<IllegalArgumentException> {
            MpzPassClaimMetadata(
                displayName = mapOf("en" to "Name"),
                description = emptyMap()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassGroupMetadata(
                displayName = mapOf("en" to "Name"),
                description = emptyMap()
            )
        }

        // minClaimsToGroup < 1 is rejected
        assertFailsWith<IllegalArgumentException> {
            MpzPassGroupMetadata(
                displayName = mapOf("en" to "Name"),
                minClaimsToGroup = 0
            )
        }

        // Referencing an unknown groupId in isoMdocClaimsMetadata or sdJwtVcClaimsMetadata is rejected
        assertFailsWith<IllegalArgumentException> {
            MpzPassMetadata(
                isoMdocClaimsMetadata = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "family_name" to MpzPassClaimMetadata(
                            displayName = mapOf("en" to "Family Name"),
                            groupId = "missing_group"
                        )
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MpzPassMetadata(
                sdJwtVcClaimsMetadata = mapOf(
                    buildJsonArray { add("family_name") } to MpzPassClaimMetadata(
                        displayName = mapOf("en" to "Family Name"),
                        groupId = "missing_group"
                    )
                )
            )
        }

        // Non-string element in sdJwtVcClaimsMetadata path is rejected
        assertFailsWith<IllegalArgumentException> {
            MpzPassMetadata(
                sdJwtVcClaimsMetadata = mapOf(
                    buildJsonArray { add(JsonPrimitive(42)) } to MpzPassClaimMetadata(
                        displayName = mapOf("en" to "Invalid Path")
                    )
                )
            )
        }
    }
}