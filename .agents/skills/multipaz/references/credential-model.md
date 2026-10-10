# Credential Model

## Use this when

- modeling stored credentials
- choosing document-type support
- mapping claims to mdoc or SD-JWT VC
- deciding whether logic can remain shared in `commonMain`

## Pinned upstream anchors

- Standard known types: `multipaz-doctypes/src/commonMain/kotlin/org/multipaz/documenttype/knowntypes`
- Utopia examples: `multipaz-utopia/src/commonMain/kotlin/org/multipaz/utopia/knowntypes`
- Test app document creation and storage: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DocumentStoreScreen.kt`
- SD-JWT VC sample provisioning: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`

## Guidance

- Treat ISO mdoc, mDL, and SD-JWT VC as credential formats or profiles.
- Do not describe QR, BLE, or NFC as credential formats.
- Keep document modeling, request construction, and trust decisions in shared code when the used APIs are multiplatform.
- Put platform transport, activities, services, and entitlements in platform code.

## Validation

- Verify the selected document type or canned request exists in the current version.
- If the user asks for mDL examples, anchor on `DrivingLicense` in `multipaz-doctypes`.
