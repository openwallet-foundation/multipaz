# Credential Issuance

## Use this when

- creating or importing credentials into a wallet
- adding provisioning UI
- wiring secure storage and document store setup

## Pinned upstream anchors

- Compose provisioning UI: `multipaz-compose/src/commonMain/kotlin/org/multipaz/compose/provisioning/ProvisioningBottomSheet.kt`
- Swift OpenID4VCI provisioning support: `samples/SwiftTestApp/SwiftTestApp/ProvisioningSupport.swift`
- Test app document creation and secure-area selection: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DocumentStoreScreen.kt`

## Normal sequence

1. Create or obtain storage.
2. Build or obtain a `SecureArea` and `SecureAreaRepository`.
3. Build a `DocumentStore`.
4. Configure document types and wallet domains.
5. Add the provisioning or issuance workflow.
6. Validate secure storage and trust handling.

## Minimum holder architecture

For a KMP holder sample, keep the architecture split explicit:

- `commonMain`: document type setup, `DocumentStore` construction, credential/domain constants, local sample credential creation, OpenID4VCI provisioning orchestration, and UI state that does not import platform APIs.
- `androidMain` or Android app module: Multipaz Android context initialization, `Intent.ACTION_VIEW` browser launch, custom scheme or app-link redirect capture, Android manifest permissions, and activity lifecycle dispatch.
- `iosMain` or Swift app code: iOS browser/redirect implementation when supported by the app. If it is not wired, fail explicitly instead of silently pretending issuance works.
- Resources: card art, sample portraits, and test trust anchors may be bundled for samples. Mark sample private keys and demo attestation material as non-production.

The minimal runtime objects are:

1. `Platform.nonBackedUpStorage` or a project-appropriate storage implementation.
2. `Platform.getSecureArea()` added to a `SecureAreaRepository`.
3. `DocumentTypeRepository` with the required doctypes, for example `DrivingLicense.getDocumentType()`.
4. `buildDocumentStore(storage, secureAreaRepository)`.
5. A provisioning handler or local credential creation path that writes credentials into the same `DocumentStore`.

## Secure local storage pattern

- Store documents through `DocumentStore`; do not persist credential blobs by hand.
- Store private keys through `SecureArea`; do not place generated credential keys in preferences, JSON, logs, or resources.
- Use `nonBackedUpStorage` for holder state that should not be cloud-restored into another device profile.
- Keep user-authentication settings deliberate. Samples may use `userAuthenticationRequired = false` for automation, but production holders should choose settings based on product and platform security requirements.
- For mdoc/MSO issuance, normalize `signedAt`, `validFrom`, and `validUntil` to whole seconds. MSO encoding rejects timestamps with fractional seconds.
- Seed trust anchors and sample certificates idempotently; repeated app launches should not fail because a trust entry already exists.
- Automated tests should verify at least document creation, document listing/loading, and credential count or credential usability when platform test infrastructure supports the selected secure area.
- Host JVM tests cannot use Android Keystore directly. For Android host tests, inject `SoftwareSecureArea` or move the test to connected-device instrumentation.

## Security requirements

- Sample-only local backends or embedded keys must be clearly marked as non-production. `ProvisioningSupport.swift` embeds keys and explicitly explains that this is not real-world safe.
- Never move sample private keys into production code.
