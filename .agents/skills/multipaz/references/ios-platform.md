# iOS Platform

## Current NFC status

Multipaz NFC credential presentation is currently not supported on iOS.

## Required implications

- Do not generate Multipaz NFC presentation code for iOS.
- Adding `CoreNFC` or an NFC entitlement does not create Multipaz NFC presentation support.
- Do not copy Android NFC APIs or Android service logic into `iosMain` or shared code.
- Do not claim that a common cross-platform NFC implementation works for both Android and iOS.
- Do not over-correct into "NFC does not exist on iOS". Upstream ships CoreNFC-backed
  *tag reading* for the verifier/reader role under
  `multipaz/src/iosMain/kotlin/org/multipaz/nfc/` (`NfcTagReader.ios.kt`,
  `NfcIsoTagIos.kt`). Reading a presented tag is not credential presentation: the
  Android-only boundary applies to the holder/presentment side only.

## Pinned upstream anchors

- iOS QR-based proximity presentment: `samples/SwiftTestApp/SwiftTestApp/Iso18013ProximityPresentmentScreen.swift`
- iOS Identity Document integration: `samples/SwiftTestApp/IdentityDocumentProviderExtension/DocumentProviderExtension.swift`
- Swift provisioning support: `samples/SwiftTestApp/SwiftTestApp/ProvisioningSupport.swift`
- KMP `iosMain` sources in the sample app: `samples/testapp/src/iosMain`

## Supported-alternative workflow

When the user asks for iOS presentment, verify the requested version and prefer one of:

- QR-based presentment
- BLE-backed proximity where the selected version and app actually support it
- browser or URI/deep-link initiated flows
- W3C Digital Credentials / Identity Document integration

## Custom URI and browser handoff

- iOS does not use Android intent filters. For browser-launched OpenID4VCI offers, register every custom scheme in the app target `Info.plist` under `CFBundleURLTypes`.
- Register the credential-offer scheme, commonly `openid-credential-offer`, and any OAuth redirect scheme used by the app metadata, such as `com.example.wallet`.
- In SwiftUI wrappers around Compose Multiplatform, add `.onOpenURL` and forward `url.absoluteString` into shared state or the KMP entry point. Scheme registration alone only lets iOS open the app; it does not deliver the URL to the holder flow unless the app handles the callback.
- For a Compose controller that only reads an initial value, rebuild the controller when a new URL arrives, for example by passing the URL into `MainViewController(initialCredentialOfferUri:)` and applying a stable `.id(...)` keyed by the URL.
- Authorization-code issuance needs a second handoff: when shared code receives an OAuth challenge, open the authorization URL on the main thread with UIKit or SwiftUI, keep a pending redirect keyed by `state`, and have `.onOpenURL` complete that pending redirect before treating the URL as a new credential offer.
- The callback path must distinguish the redirect scheme, such as `com.example.wallet:/openid4vci?...`, from the offer scheme `openid-credential-offer://...`.
- For device testing, use Safari or the issuer website on the physical device. For simulator smoke tests, install the app and run `xcrun simctl openurl booted 'openid-credential-offer://...'`.

## Xcode linker requirements

- Multipaz iOS apps that use the KMP framework and storage may need the iOS app target to link SQLite explicitly. If Xcode reports undefined symbols such as `_sqlite3_bind_blob`, `_sqlite3_open_v2`, `_sqlite3_prepare16_v2`, or `_sqlite3_step`, add `OTHER_LDFLAGS = ("$(inherited)", "-lsqlite3")` to each `XCBuildConfiguration` for the iOS app target in `iosApp.xcodeproj/project.pbxproj`.
- Apply the flag to the app target configurations, not only the project-level configurations. Confirm with `xcodebuild -project iosApp/iosApp.xcodeproj -target iosApp -configuration Debug -sdk iphonesimulator -showBuildSettings | rg OTHER_LDFLAGS`.

## Validation focus

- Verify the supported iOS mechanism actually used by the project.
- Inspect plist, associated domains, and extension entitlements only for supported iOS features.
