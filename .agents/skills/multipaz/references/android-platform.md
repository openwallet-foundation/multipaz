# Android Platform

## Current NFC status

Multipaz NFC credential presentation is supported on Android in the pinned upstream source when the app includes the required services, manifest entries, and device capabilities. Verify the selected target version before relying on this capability statement.

## Pinned upstream anchors

- Manifest declarations: `samples/testapp/src/androidMain/AndroidManifest.xml`
- Combined APDU service: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppCombinedNfcService.kt`
- NDEF engagement service: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppMdocNdefService.kt`
- NFC v2 engagement service: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppMdocNfcV2Service.kt`
- Digital Credentials API activity: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppCredentialManagerPresentmentActivity.kt`
- URI-scheme presentment activity: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppUriSchemePresentmentActivity.kt`

## Android holder and issuance requirements

- Call `org.multipaz.context.initializeApplication(applicationContext)` from Android app startup before shared wallet initialization touches `Platform.storage`, `Platform.nonBackedUpStorage`, or secure areas.
- Add `android.permission.INTERNET` for any real issuer, verifier, or metadata fetch.
- For QR presentment over BLE, add Bluetooth LE capability and permissions. On Android 12 and later declare and request runtime `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, and `BLUETOOTH_CONNECT`; declare `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` when the scan result is not used to infer location. For Android 11 and lower include legacy Bluetooth permissions plus coarse/fine location as required by the selected BLE operations.
- Prefer `multipaz-compose` helpers `rememberBluetoothPermissionState()` and `rememberBluetoothEnabledState()` in Compose holder UI so the user can grant BLE permissions or enable Bluetooth before QR generation.
- Use `Platform.promptModel` with `PromptDialogs(promptModel)` for Compose presentment consent. On Android, the host activity should extend `FragmentActivity` and include `androidx.fragment:fragment`; Multipaz prompt dialogs rely on fragment-backed platform UI such as biometric and consent prompts.
- `multipaz-compose` contributes `org.multipaz.compose.prompt.PresentmentActivity` through its Android manifest. Verify the merged manifest and keep app `minSdk` compatible with the selected Multipaz Compose artifact.
- For OpenID4VCI custom scheme offers, add an intent filter for the credential-offer scheme and dispatch both `onCreate` and `onNewIntent` data.
- For OAuth authorization-code offers, add a redirect scheme or app link that exactly matches the client metadata sent to the issuer. Use `singleTop` or equivalent routing when needed so the redirect reaches the existing activity.
- Keep Android browser launching and redirect capture in Android code. Shared code may expose an `expect` authorization function, but the `actual` implementation should own `Intent.ACTION_VIEW` and redirect completion.

## Verified requirements to inspect

- `android.permission.NFC`
- `android.hardware.nfc`
- `android.permission.BIND_NFC_SERVICE` on the APDU services
- activity and service registration in the Android manifest
- NFC adapter and lifecycle handling in app code
- Bluetooth LE feature declaration, Bluetooth permissions including `BLUETOOTH_SCAN` with appropriate scan flags, runtime permission request, Bluetooth-enabled handling, and `FragmentActivity`/`PromptDialogs` consent wiring for QR/BLE presentment
- OpenID4VCI offer and OAuth redirect intent filters when issuance is in scope
- physical-device availability for runtime testing

## Source-set rules

- Keep Android NFC code in `androidMain` or Android application modules.
- Do not move APDU services, `PresentmentActivity`, or Android manifest wiring into `commonMain`.
- Keep Android app context initialization, browser intents, and Android URI dispatch in Android-specific source sets or the Android app module.

## Validation

- Compile Android sources after edits.
- Check manifest declarations.
- For QR/BLE presentment, run a physical-device test with a compatible verifier: grant Bluetooth permissions, create/load a credential, generate QR, scan from verifier, approve consent, and confirm verifier validation.
- For issuance, run a device smoke test with a real offer when possible: offer received, browser opened, redirect returned, document stored.
- Note that runtime NFC behavior usually needs physical hardware.
