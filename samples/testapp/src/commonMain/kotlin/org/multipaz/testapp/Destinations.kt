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
data class TrustPointViewerDestination(
    val trustManagerId: String,
    val trustPointId: String
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
data object ProvisioningTestDestination: Destination()

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
