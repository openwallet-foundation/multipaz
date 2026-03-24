package org.multipaz.mpzpass

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.securearea.CreateKeySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MpzPassTest {

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
        val importedDoc = harness.documentStore.importMpzPass(pass)
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
        val importedDoc = harness.documentStore.importMpzPass(pass)
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
        val importedDoc = harness.documentStore.importMpzPass(pass)
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

}