package org.multipaz.testapp

import kotlinx.serialization.Serializable

@Serializable
sealed class Destination

@Serializable
data object StartDestination: Destination()

@Serializable
data object SettingsDestination: Destination()

@Serializable
data object AboutDestination: Destination()

@Serializable
data object DocumentStoreDestination: Destination()

@Serializable
data class DocumentViewerDestination(
    val documentId: String
): Destination()

@Serializable
data class CredentialViewerDestination(
    val documentId: String,
    val credentialId: String
): Destination()

@Serializable
data class CredentialClaimsViewerDestination(
    val documentId: String,
    val credentialId: String
): Destination()

@Serializable
data object TrustedIssuersDestination: Destination()

@Serializable
data object TrustedVerifiersDestination: Destination()

@Serializable
data class TrustEntryDestination(
    val trustManagerId: String,
    val trustEntryId: String,
    val justImported: Boolean = false
): Destination()

@Serializable
data class TrustEntryEditDestination(
    val trustManagerId: String,
    val trustEntryId: String
): Destination()

@Serializable
data class TrustEntryVicalEntryDestination(
    val trustManagerId: String,
    val trustEntryId: String,
    val vicalCertNumber: Int
): Destination()

@Serializable
data class TrustEntryRicalEntryDestination(
    val trustManagerId: String,
    val trustEntryId: String,
    val ricalCertNumber: Int
): Destination()

@Serializable
data object SoftwareSecureAreaDestination: Destination()

@Serializable
data object AndroidKeystoreSecureAreaDestination: Destination()

@Serializable
data object SecureEnclaveSecureAreaDestination: Destination()

@Serializable
data object CloudSecureAreaDestination: Destination()

@Serializable
data object PassphraseEntryFieldDestination: Destination()

@Serializable
data object PassphrasePromptDestination: Destination()

@Serializable
data object ConsentPromptDestination: Destination()

@Serializable
data object QrCodesDestination: Destination()

@Serializable
data object NfcDestination: Destination()

@Serializable
data object IsoMdocProximitySharingDestination: Destination()

@Serializable
data object IsoMdocProximityReadingDestination: Destination()

@Serializable
data object DcRequestDestination: Destination()

@Serializable
data class ShowResponseDestination(
    val vpResponse: String?,
    val deviceResponse: String?,
    val sessionTranscript: String,
    val nonce: String?,
    val eReaderKey: String?,
    val metadata: String
): Destination()

@Serializable
data object IsoMdocMultiDeviceTestingDestination: Destination()

@Serializable
data object CertificatesViewerExamplesDestination: Destination()

@Serializable
data class CertificateViewerDestination(
    val certificateData: String,
) : Destination()

@Serializable
data object RichTextDestination: Destination()

@Serializable
data object NotificationsDestination: Destination()

@Serializable
data object ScreenLockDestination: Destination()

@Serializable
data object PickersDestination: Destination()

@Serializable
data object NfcReadersDestination: Destination()

@Serializable
data class NfcReaderDestination(
    val readerId: String
): Destination()

@Serializable
data object DocumentListDestination: Destination()

@Serializable
data object EventLogDestination: Destination()

@Serializable
data class EventViewerDestination(
    val eventId: String,
): Destination()

@Serializable
data object ShareSheetDestination: Destination()


@Serializable
data object GenerateMpzPassDestination: Destination()

@Serializable
data object FloatingItemListDestination: Destination()

@Serializable
data object DeviceCheckDestination: Destination()
