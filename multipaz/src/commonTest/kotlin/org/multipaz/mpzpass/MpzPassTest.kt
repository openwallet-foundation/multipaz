package org.multipaz.mpzpass

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.ImportMpzPassException
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
}