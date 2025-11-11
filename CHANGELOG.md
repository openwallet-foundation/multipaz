# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.95.0] - 2025-11-11
Changes since Multipaz 0.94.0 include:
- Longfellow updates to latest version and compliance with latest 18013-5 Second Edition draft
- Support for Longfellow on MacOS arm64.
- Support for parsing and generating X.509 CRLs
- Merge multipaz-models library into multipaz library.
- Move W3C DC API implementations into multipaz-dcapi library.
- Support for [Observe Mode](https://github.com/kormax/apple-enhanced-contactless-polling)
- New AsymmetricKey abstraction for nicer APIs supporting both local and remote keys.
- OpenID4VCI fixes.
- Age Verification doctype updates.
- SecureArea improvements for unlocking.
- W3C Digital Credentials API app-to-app presentment fixes on Android
- Improved HKDF and HPKE implementations
- Move some code to new [Multipaz Extras repository](https://github.com/openwallet-foundation/multipaz-extras), including multipaz-vision library.
- Support for external NFC readers connected via USB in Multipaz Test App.
- Bug fixes.

## [0.94.0] - 2025-09-26
Changes since Multipaz 0.93.0 include:
- Improved and standards-compliant DCQL support in OpenID4VP implementation.
- Generalizing of the Multipaz X.509 extension including adding support for
  using this extension on reader certificates for conveying reader identity.
- OpenID4VCI support with `ProvisioningModel` and `ProvisioningClient`.
- Support for querying and setting whether Bluetooth is enabled.
- High-level composable for simpler QR mdoc presentment.
- Support for creating AttestKey in Android Keystore Secure Area.
- Native support for W3 Digital Credentials API on Android.
- New `org.multipaz.verification` package with utility functions to
  make it easier to create W3C DC requests and validate W3C DC Responses.
- Support for EU Age Verification ISO mdoc document type (`eu.europa.ec.av.1`)
  with support for this in both the issuer, testapp, and verifier.
- Support for 18013-5 Second Edition in `DeviceRequest` and
  `DeviceEngagement`. Additional support 18013-5 Second Edition will appear
  in future releases.
- New `org.multipaz.loyality.1` ISO mdoc document type, for use in samples
  and codelabs.
- Update PhotoID document type to latest ISO/IEC 23220-4 draft.
- System of Record and Issuer enhancements.
- NFC Reading enhancements.

## [0.93.0] - 2025-08-04
Changes since Multipaz 0.92.1 include:
- TrustManager rearchitecture to better support applications allowing
  the user to add/remove/edit entries, e.g. [Multipaz Identity Reader](https://apps.multipaz.org).
- W3C Digital Credential improvements on Android to include the Exchange Protocol, as required.
- Make DeviceRequestGenerator work with keys in a `SecureArea`.
- New system-of-record server and OpenID4VCI interoperability fixes.
- Allow scanning NFC without a dialog, for platforms that support it (e.g. Android).
- Support for [Longfellow ZK](https://github.com/google/longfellow-zk) and new ZKP plumbing in
  latest [ISO/IEC 18013-5 Second Edition drafts](https://github.com/ISOWG10/ISO-18013).
- OpenID4VP 1.0 support using URI schemes.
- Support requests with multiple protocols in W3C DC API in verifier.multipaz.org and properly
  handle it in Multipaz Test App.
- Unit tests for Android Credential Manager Matcher.
- Face matching support in multipaz-vision, using [LiteRT](https://ai.google.dev/edge/litert)
  and [FaceNet](https://en.wikipedia.org/wiki/FaceNet).
- Update of all dependencies to latest version, including kotlinx-datetime 0.7.1 which includes
  the migration of `Instant` and `Clock` from `kotlinx.datetime` to `kotlin.time`.
- Bug fixes and other enhancements.

## [0.92.1] - 2025-06-27
Changes since Multipaz 0.92.1 include:
- Fix BLE GATT on older Android devices.
- Pass the full JWS for OpenID4VCI key attestations, not just the body of the JWT.

## [0.92.0] - 2025-06-25
Changes since Multipaz 0.91 include:
- Face Detection routines and Selfie detection, using MLKit
- BLE GATT data race fixes.
- Update SD-JWT implementation to properly handle non-top-level disclosures.
- Update JWE, JWS routines to work on Kotlin Multiplatform and drop dependency on Nimbus library.
- Update to latest W3C Digital Credential API.
- OpenID4VP Draft 29 support.
- OpenID4VCI interoperability fixes.
- Removal of BouncyCastle Dependency.
- Consent Prompt enhancements.
- Exporting of Android services/activities to make W3C DC API and NFC Engagement easier to implement.
- Fix for Annex C implementation discovered during interop testing ("Response" -> "response").
- Migration of all server code to use Ktor environment, making it easier to deploy.
- Stand up at https://verifier.multipaz.org, https://issuer.multipaz.org, and https://csa.multipaz.org.
- New "Raw DCQL" box in online verifier code.
- Rework QR code generation and scanning and drop some dependencies.
- Generate and publish Multipaz.xcframework w/ Package.swift file for easy consumption in Swift projects.

## [0.91.0] - 2025-05-30
Changes since Multipaz 0.90 include
- Fix generated reader auth certificates to contain required extensions from 
  ISO/IEC 18013-5:2021 Annex B.1.7.
- Multiplatform Camera composable in `multipaz-compose` library.
- OpenID4VP draft 24 updates, based on interoperability testing.
- OpenID4VCI updates.
- Preliminary ZKP support.
- Preliminary support for scanning barcodes, using MLKit.
- Updated RPC schema hash calculations to better support with different Kotlin versions.
- Better support for semantic versioning.
- New icons.
- New https://apps.multipaz.org/ website with prebuilt APKs.

## [0.90] - 2025-03-26
This is the first release with the CHANGELOG.md file and also the first release using the new
Multipaz name. Main changes since the Identity Credential version 202411.1 include:
- The project has been renamed to Multipaz and all package names have been
  changed from `com.android.identity` to `org.multipaz`.
- New `multipaz-models` and `multipaz-compose` libraries
- Removal of `appholder` module.
- Conversion of DocumentStore, Document, Credential to use suspend methods.
- Conversion of SecureArea to use suspend methods.
- Introduction of new PromptModel to handle interaction with the user for
  asynchronous methods on SecureArea and NFC tag reading.
- New multiplatform ASN.1 and X.509 code.
- New multiplatform ISO/IEC 18013-5 proximity presentment stack.
- New multiplatform NFC stack and NFC tag reader abstraction.
- New multiplatform storage backend.
- New presentment model and composable.
- New HTML/Markdown, Notifications, LockScreen multiplatform composables.
