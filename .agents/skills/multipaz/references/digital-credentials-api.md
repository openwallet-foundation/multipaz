# Digital Credentials API

## Use this when

- implementing W3C Digital Credentials API presentment
- wiring Android credential-manager entry points
- integrating iOS Identity Document services

## Pinned upstream anchors

- Android module: `multipaz-dcapi`
- Android presentment activity sample: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppCredentialManagerPresentmentActivity.kt`
- Android manifest registration: `samples/testapp/src/androidMain/AndroidManifest.xml`
- iOS Identity Document provider extension: `samples/SwiftTestApp/IdentityDocumentProviderExtension/DocumentProviderExtension.swift`

## Guidance

- Validate the platform-specific entry point before adding shared logic.
- Treat Android credential manager and iOS Identity Document integration as different platform surfaces even when shared document logic exists underneath.
- Keep platform registration, entitlements, and activity or extension code out of `commonMain`.
