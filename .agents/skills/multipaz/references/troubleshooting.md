# Troubleshooting

## Method

1. Inspect the project and dependency layout.
2. Reproduce the failure at the narrowest module or platform boundary.
3. Check whether the code path is shared, Android-only, or iOS-only.
4. Compare against the closest matching Multipaz sample or test.
5. Verify trust, storage, and transport assumptions separately.

## Common failure classes

- wrong or mixed Multipaz versions
- missing Android manifest entries
- Android-only APIs referenced from `commonMain` or `iosMain`
- unsupported iOS NFC assumptions
- missing redirect or URI-scheme configuration for OpenID4VCI or OpenID4VP
- OpenID4VCI offer appears in the app but tapping issue does nothing: inspect device logs first, then check Android Multipaz context initialization, internet permission, Ktor engine, OAuth challenge handling, redirect intent filter, and issuer trust in that order.
- iOS website click on `openid-credential-offer://...` does not open or populate the app: check the app target `Info.plist` for `CFBundleURLTypes` with the offer scheme, then verify SwiftUI/UIKit handles the opened URL with `.onOpenURL` or equivalent and forwards `url.absoluteString` into shared code.
- iOS offer fills the app but tapping `Issue from offer` does not open the browser: check that the iOS `authorizeOAuth` actual is not a stub, opens the OAuth challenge URL on the main thread, stores a pending redirect keyed by `state`, and that `.onOpenURL` completes that pending redirect for the OAuth redirect scheme before handling offer links.
- `NullPointerException` in `ContextUtil.getApplicationContext`: Android app did not call `initializeApplication(applicationContext)` before using Multipaz `Platform` storage or secure-area helpers.
- `Permission denied (missing INTERNET permission?)`: Android manifest is missing `android.permission.INTERNET`.
- `CA not registered: trusted_client_attestations...`: the issuer does not trust the wallet attestation key. Use a registered wallet backend, trusted sample keys for Multipaz demo issuers, or configure a local issuer to trust the test key.
- `Failed to find HTTP client engine implementation`: Ktor core is present but no platform engine is on the runtime classpath. Add the Android, Darwin, CIO, or other appropriate Ktor client engine in the correct source set and prefer explicit engine construction.
- iOS Xcode link fails with undefined `_sqlite3_*` symbols: the app target is not linking SQLite. Add `OTHER_LDFLAGS = ("$(inherited)", "-lsqlite3")` to every `XCBuildConfiguration` under the iOS app target in `iosApp.xcodeproj/project.pbxproj`, then verify `xcodebuild -showBuildSettings` reports `-lsqlite3`.
- `signedAt cannot have fractional seconds`: mdoc/MSO timestamps were created from `Clock.System.now()` without truncating to whole seconds. Normalize issue and validity times before encoding.
- `NoSuchProviderException: no such provider: AndroidKeyStore` in host tests: the host JVM cannot use Android Keystore. Inject `SoftwareSecureArea` for host tests or run a connected Android device test.
- QR presentment shows `Credential presentation failed: Error satisfying the request`: Multipaz received a verifier request but `DeviceRequest.execute()` found no matching credential. Check that at least one stored credential is an `MdocCredential` with the requested `docType` and requested claims, not only an SD-JWT or incompatible issued document. An issued credential can have the right docType and still fail if it lacks requested data elements. Avoid passing `preselectedDocuments` unless that document is known to satisfy the request; otherwise pass `emptyList()` and seed a local sample mDL for demo verification. Confirm the fix with a physical verifier scan.
- QR is shown but the verifier cannot establish BLE transport: verify Bluetooth is enabled, Android 12+ permissions include `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, and `BLUETOOTH_CONNECT`, and the app gates QR generation on `rememberBluetoothPermissionState()` plus `rememberBluetoothEnabledState()`.
- QR/BLE appears to do nothing, but logcat shows `DeviceRequest` followed by `PresentmentActivity: in onStop(), canceling`: BLE and request parsing are working, but consent/prompt integration is wrong. Align with the Compose samples by using `Platform.promptModel`, rendering `PromptDialogs(promptModel)`, hosting the app in `FragmentActivity`, and adding `androidx.fragment:fragment`.
- Android log lines like `hiddenapi: Accessing hidden method ... allowed` are warnings when marked `allowed`; do not treat them as the root cause unless paired with a real exception.
- trust-chain or certificate mismatch
- secure-area or storage setup mismatch

## Helpful anchors

- Dependency problems: follow [dependency-guide.md](dependency-guide.md) to inspect declarations, version aliases, and the affected module's resolved dependencies.
- Android manifest and service wiring: `samples/testapp/src/androidMain/AndroidManifest.xml`
- iOS supported presentment paths: `samples/SwiftTestApp/SwiftTestApp/Iso18013ProximityPresentmentScreen.swift`
- Trust and certificate examples: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
