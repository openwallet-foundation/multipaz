# Architecture

## Pinned upstream module map

Derived from the upstream `settings.gradle.kts` at the commit recorded in
[upstream-source.md](upstream-source.md), including tooling and test modules but
not included builds such as `build-logic`. This is not a claim about the target
project's module layout.

- `:multipaz`: core KMP library for credential, crypto, CBOR, trust, mdoc transport, presentment, storage, and secure-area APIs.
- `:multipaz-compose`: Compose Multiplatform UI and Android integration helpers, including presentment and NFC service wrappers.
- `:multipaz-swiftui`: SwiftUI-facing module exported for Apple targets.
- `:multipaz-dcapi`: W3C Digital Credentials API support with Android-specific dependencies.
- `:multipaz-doctypes`: standard document types such as mDL and Photo ID.
- `:multipaz-utopia`: Utopia example document types and requests.
- `:multipaz-longfellow`: Zero-knowledge proof integration.
- `:multipaz-openid4vci`: OpenID4VCI protocol module; inspect version-matched
  holder and server samples before selecting it.
- `:multipaz-verifier`: verifier-side client logic.
- `:multipaz-csa`: Cloud Secure Area support.
- `:multipaz-cbor-rpc`: multiplatform RPC support used by backend-linked flows.
- Server modules: `:multipaz-server`, `:multipaz-backend-server`, `:multipaz-openid4vci-server`, `:multipaz-verifier-server`, `:multipaz-csa-server`, `:multipaz-records-server`, `:multipaz-server-frontend`, `:multipaz-server-deployment`.
- Samples: `:samples:testapp`, `:samples:SwiftTestApp`.
- Other included modules at this pin: `:multipaz-records`, `:multipaz-android-legacy`
  (legacy Android identity APIs), `:multipaz:SwiftBridge` (Xcode project bridging
  the Kotlin framework to Swift), `:xcframework` (builds the aggregated `Multipaz`
  iOS XCFramework), `:multipazctl` (command-line tool), `:multipaz-tools:web` and
  `:multipaz-tools:server` (tools.multipaz.org), and the test-only
  `:multipaz-dcapi:matcherTest`.

## Important sample anchors

- Kotlin Multiplatform wallet and integration testbed: `samples/testapp`
- Native SwiftUI wallet and iOS extension sample: `samples/SwiftTestApp`
- Android NFC presentment wiring: `samples/testapp/src/androidMain/AndroidManifest.xml`
- Android NFC APDU services: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppCombinedNfcService.kt`
- Swift iOS QR presentment: `samples/SwiftTestApp/SwiftTestApp/Iso18013ProximityPresentmentScreen.swift`
- Swift iOS Identity Document provider integration: `samples/SwiftTestApp/IdentityDocumentProviderExtension/DocumentProviderExtension.swift`

## Source-set pattern

- Shared business logic typically lives in `commonMain`.
- Android-specific app integration, manifests, permissions, and NFC services live in `androidMain`.
- iOS Kotlin interop code lives in `iosMain`.
- Native Swift and SwiftUI integration lives in `samples/SwiftTestApp/SwiftTestApp`.

## How to use this reference

- Read this file first when you need to decide where a change belongs.
- Follow with [dependency-guide.md](dependency-guide.md) for dependency selection and [android-platform.md](android-platform.md) or [ios-platform.md](ios-platform.md) for platform wiring.
