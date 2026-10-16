# OpenID4VP

## Use this when

- adding holder-side OpenID4VP presentment
- wiring URI scheme or browser-based launch points
- building verifier requests that embed OpenID4VP payloads

## Pinned upstream anchors

- Android URI-scheme activity: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppUriSchemePresentmentActivity.kt`
- Android manifest scheme handling: `samples/testapp/src/androidMain/AndroidManifest.xml`
- Request-building examples: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
- DC request combinations: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DcRequestScreen.kt`

## Guidance

- Verify whether the flow is URI-scheme based, browser initiated, or combined with ISO 18013-7 / DC API behavior.
- Keep entry-point wiring platform specific.
- Keep request composition and credential selection logic shared when the APIs are multiplatform.
