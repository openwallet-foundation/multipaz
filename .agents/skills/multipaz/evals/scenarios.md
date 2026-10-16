# Scenarios

## 1. Inspect an existing KMP project and report whether Multipaz is already configured correctly

- Input task: Inspect this Kotlin Multiplatform wallet project and tell me whether Multipaz is configured correctly.
- Expected skill behavior: read the project configuration and source layout, classify modules and targets, summarize dependency style, and identify missing or suspicious setup.
- Required inspection steps: inspect Gradle wrapper, versions, targets, source sets, Multipaz dependencies, manifests, plists, and entitlements.
- Mistakes to avoid: skipping inspection, guessing versions, or assuming Android and iOS feature parity.
- Evidence of success: a report grounded in actual files and dependencies.
- Validation expectations: cite the actual configuration files and source locations supporting the module, version, dependency, and platform findings; no bundled scripts are required.

## 2. Add the minimum dependencies required for a basic holder application

- Input task: Add the minimum Multipaz dependencies for a holder app.
- Expected skill behavior: choose only core holder modules and match the project's dependency declaration style.
- Required inspection steps: inspect current dependency style and whether Compose, SwiftUI, or dcapi is already used.
- Mistakes to avoid: adding verifier, server, or Android-only modules without evidence.
- Evidence of success: minimal, version-aligned dependency changes.
- Validation expectations: changed declarations remain version-aligned and correctly scoped; relevant existing compile tasks pass.

## 3. Create and securely store a locally generated credential

- Input task: Create a local test credential and store it safely.
- Expected skill behavior: use `DocumentStore`, `SecureArea`, and sample-backed document type setup.
- Required inspection steps: locate secure-area and document-store patterns in the target project or samples; identify which pieces belong in `commonMain`, Android startup, and platform redirect handlers.
- Mistakes to avoid: storing secrets in plaintext without calling it sample-only.
- Evidence of success: document creation and storage paths match current APIs.
- Validation expectations: relevant compile targets pass; automated tests or a platform smoke test verify document creation, listing/loading, and credential count when practical.

## 4. Add an OpenID4VCI issuance flow

- Input task: Add OpenID4VCI provisioning.
- Expected skill behavior: choose the wallet-side provisioning path, verify redirect wiring, add platform Ktor client engines when constructing `HttpClient`, initialize Android Multipaz application context, add internet permission, handle OAuth redirects, and keep backend secrets out of app code.
- Required inspection steps: inspect deep links, app links, current dependencies, and version-compatible OpenID4VCI support.
- Mistakes to avoid: embedding production keys, inventing backend APIs, adding the server-oriented OpenID4VCI artifact to a mobile holder without sample evidence, leaving `HttpClient()` without a platform engine, or using unregistered generated wallet attestation keys against a public issuer.
- Evidence of success: redirect handling and backend integration are grounded in current samples or modules.
- Validation expectations: dependency inspection, targeted app/shared compile checks, and a runtime launch or explicit engine-classpath check when possible. For a real issuer, verify offer receipt, browser authorization, redirect return, and document-store insertion.

## 5. Add QR-based credential presentation for Android and iOS where supported

- Input task: Add QR-based presentment for both mobile platforms.
- Expected skill behavior: use shared presentment logic where supported and platform-specific entry points for Android and iOS.
- Required inspection steps: verify QR presentment support in the selected version and inspect Android and iOS samples.
- Mistakes to avoid: claiming QR implies NFC or skipping platform entry points.
- Evidence of success: Android and iOS implementations cite sample-backed files.
- Validation expectations: Android compile and iOS compile when available.

## 6. Add NFC credential presentation to an Android application

- Input task: Add Multipaz NFC presentment to Android.
- Expected skill behavior: wire manifest declarations, APDU services, and Android-only service classes from verified samples.
- Required inspection steps: inspect Android manifest, NFC service classes, and presentment activity patterns.
- Mistakes to avoid: placing Android NFC logic in `commonMain`.
- Evidence of success: Android-specific code only, with manifest support.
- Validation expectations: Android compile, manifest inspection, and note that hardware runtime testing is still needed.

## 7. Respond correctly when asked to add Multipaz NFC credential presentation to iOS

- Input task: Add Multipaz NFC presentment to iOS.
- Expected skill behavior: refuse to generate iOS NFC presentment code, explain the limitation, and recommend a verified alternative.
- Required inspection steps: verify current repository state and inspect iOS-supported alternatives.
- Mistakes to avoid: adding `CoreNFC`, entitlements, or copied Android logic.
- Evidence of success: no iOS NFC code is generated.
- Validation expectations: the response explicitly states that Multipaz NFC credential presentation is currently not supported on iOS.

## 8. Respond correctly when asked to create one shared NFC implementation for Android and iOS

- Input task: Make one shared NFC implementation for both platforms.
- Expected skill behavior: explain that shared protocol logic may exist, but Multipaz NFC transport and engagement code cannot be shared across Android and iOS as a supported feature today.
- Required inspection steps: verify Android sample support and iOS lack of supported NFC presentment.
- Mistakes to avoid: moving NFC transport code into `commonMain`.
- Evidence of success: platform capability difference is clear and Android-only code stays Android-only.
- Validation expectations: the answer rejects unsupported shared iOS NFC implementation.

## 9. Add BLE proximity presentation where supported by the current version

- Input task: Add BLE proximity presentment.
- Expected skill behavior: verify BLE support in the selected version and use sample-backed connection method or transport settings.
- Required inspection steps: inspect presentment settings and BLE-related manifest or plist usage.
- Mistakes to avoid: treating BLE as a credential format or assuming it is interchangeable with NFC.
- Evidence of success: BLE support is version-verified and platform-scoped.
- Validation expectations: compile relevant targets.

## 10. Add W3C Digital Credentials API presentation

- Input task: Add Digital Credentials API support.
- Expected skill behavior: choose `multipaz-dcapi` and the proper Android or iOS integration surface.
- Required inspection steps: inspect current dependencies and platform entry points.
- Mistakes to avoid: conflating Android credential manager with iOS Identity Document provider details.
- Evidence of success: platform-specific registration and shared document logic are separated.
- Validation expectations: relevant compile targets pass.

## 11. Create a verifier request using DCQL

- Input task: Create a DCQL verifier request.
- Expected skill behavior: build from sample-backed request formats and current document types.
- Required inspection steps: inspect DCQL examples and requested credential format.
- Mistakes to avoid: inventing request fields or confusing protocol and transport.
- Evidence of success: request structure matches sample-backed current behavior.
- Validation expectations: request is grounded in current examples.

## 12. Diagnose an Android NFC declaration or lifecycle problem

- Input task: Diagnose why Android NFC presentment is not starting.
- Expected skill behavior: inspect manifest declarations, service registration, and lifecycle hooks.
- Required inspection steps: check `android.permission.NFC`, `android.hardware.nfc`, `BIND_NFC_SERVICE`, and NFC adapter setup.
- Mistakes to avoid: generic Android NFC advice unrelated to Multipaz.
- Evidence of success: diagnosis cites the concrete missing or incorrect file entries.
- Validation expectations: Android compile and manifest review.

## 13. Diagnose an iOS entitlement or framework problem for a supported iOS feature

- Input task: Fix an iOS Identity Document or QR presentment integration issue.
- Expected skill behavior: focus on supported iOS features such as QR or Identity Document integration.
- Required inspection steps: inspect plist, entitlements, extension registration, and Swift wiring.
- Mistakes to avoid: pivoting the diagnosis into unsupported iOS NFC presentment.
- Evidence of success: fix is grounded in supported iOS code paths.
- Validation expectations: iOS compile when tooling is available.

## 14. Diagnose a Multipaz version and sample mismatch

- Input task: This project copied code from a newer sample and now fails.
- Expected skill behavior: identify version mismatch and separate upgrade decisions from implementation fixes.
- Required inspection steps: inspect current Multipaz version, current APIs, and sample provenance.
- Mistakes to avoid: silently upgrading all dependencies.
- Evidence of success: mismatch is explained with specific version evidence.
- Validation expectations: concrete dependency declarations, resolved dependency reports, or build output support the conclusion.

## 15. Explain and repair a certificate trust-chain configuration problem

- Input task: The verifier parses the request but trust verification fails.
- Expected skill behavior: distinguish parsing from trust and inspect trust anchors, signer chain, and metadata.
- Required inspection steps: inspect trust-manager usage and certificate construction flow.
- Mistakes to avoid: treating successful parsing as trust.
- Evidence of success: trust-chain defect and repair path are explicit.
- Validation expectations: compile and, when available, relevant tests.

## 16. Upgrade an older project to the current Multipaz version without upgrading unrelated dependencies

- Input task: Upgrade this wallet to the current Multipaz release only.
- Expected skill behavior: inspect current dependency state, update only Multipaz dependencies, explain breakage, and validate affected targets.
- Required inspection steps: version inspection, dependency scan, sample comparison, and platform impact review.
- Mistakes to avoid: upgrading Kotlin, AGP, Compose, or unrelated libraries without cause.
- Evidence of success: only relevant dependencies change.
- Validation expectations: dependency check and targeted builds pass.

## 17. Identify insecure sample configuration and propose production-safe changes

- Input task: Review this sample integration for production risks.
- Expected skill behavior: flag embedded keys, plaintext storage, disabled trust, or verbose sensitive logging.
- Required inspection steps: inspect provisioning support, secure-area usage, and logging.
- Mistakes to avoid: approving sample shortcuts as production-safe.
- Evidence of success: findings are specific and actionable.
- Validation expectations: no new insecure defaults introduced.

## 18. Explain which code belongs in `commonMain`, `androidMain`, and iOS source sets

- Input task: Help me split this Multipaz code across source sets.
- Expected skill behavior: place document and protocol logic in shared code when supported, and platform wiring in platform source sets.
- Required inspection steps: inspect API usage and platform imports.
- Mistakes to avoid: broad claims that everything in a holder flow is shared.
- Evidence of success: source-set split maps to current APIs.
- Validation expectations: compile affected targets.

## 19. Detect Android-only NFC code that was incorrectly placed in `commonMain`

- Input task: Find Android-only NFC code in shared code.
- Expected skill behavior: search shared and iOS source sets for Android-specific imports and presentment classes, inspect each match, and flag actual platform-boundary violations.
- Required inspection steps: scan `commonMain` and iOS sources for Android-specific imports and presentment classes.
- Mistakes to avoid: missing `PresentmentActivity`, Android manifest assumptions, or APDU services.
- Evidence of success: flagged files and markers are concrete.
- Validation expectations: cite the offending files and imports or API usage, and explain their source-set placement; do not infer a violation from a keyword match alone.

## 20. Recommend supported iOS alternatives when a developer asks for NFC presentation

- Input task: I need iOS NFC, what should I do instead?
- Expected skill behavior: explain the unsupported status and recommend only version-verified alternatives such as QR, BLE-backed flows, browser, or Identity Document integration.
- Required inspection steps: inspect current repo support and selected app architecture.
- Mistakes to avoid: recommending every alternative without checking availability.
- Evidence of success: the alternative path is explicitly verified against the current version.
- Validation expectations: response preserves the Android-only NFC boundary.

## 21. Work safely when the target project has no Multipaz source checkout

- Input task: Add Android NFC presentment to this KMP app. It has no Multipaz
  dependency and no local `samples/testapp` directory.
- Expected skill behavior: inspect the target, report the missing Multipaz version
  and sample evidence, and avoid claiming upstream paths are locally verified.
- Required inspection steps: inspect Gradle dependencies and module layout; identify
  whether a compatible Multipaz source checkout or release sample is available.
- Mistakes to avoid: copying a pinned-upstream API without version compatibility,
  inventing a dependency version, or treating an upstream sample path as a target
  project path.
- Evidence of success: the response states what evidence is missing and asks for or
  locates version-matched source before producing implementation code.
- Validation expectations: file and dependency evidence supports the conclusion.

## 22. Build a verifier request with DCQL and OpenID4VP for a verifier app

- Input task: Add a verifier request that asks for an mDL and return it over OpenID4VP.
- Expected skill behavior: separate request construction from holder transport, build the DCQL from a verified document type, carry requester trust instead of treating a parsed request as trusted, and keep request composition in shared code while entry-point wiring stays platform specific.
- Required inspection steps: inspect existing request models, canned requests, document type metadata, and whether the project already has verifier or verifier-server code.
- Mistakes to avoid: inventing DCQL fields, describing DCQL or OpenID4VP as a transport, or putting URI-scheme/browser entry points in `commonMain`.
- Evidence of success: the request matches sample-backed DCQL and the protocol/transport split is stated explicitly.
- Validation expectations: the request parses against a verified document type, and affected targets compile.

## 23. Wire a server-side issuer or verifier module

- Input task: Stand up a local OpenID4VCI issuer using Multipaz server modules.
- Expected skill behavior: choose the server-oriented modules (`:multipaz-openid4vci`, `:multipaz-openid4vci-server`, `:multipaz-backend-server`, `:multipaz-verifier-server`) instead of mobile holder artifacts, keep backend secrets out of app code, and treat embedded keys as sample-only.
- Required inspection steps: inspect the target's server build files, resources, and whether a backend module already exists; verify the module names against [references/architecture.md](../references/architecture.md).
- Mistakes to avoid: adding a server artifact to a shared mobile module, adding a server module to a holder app, or presenting a local test stub as a production issuer.
- Evidence of success: module choices and trust configuration are grounded in the pinned module map and backend sample.
- Validation expectations: the server module's own build/test tasks run, and no client-side dependency changes are made without cause.

## 24. Do not apply this skill to work that does not involve Multipaz

- Input task: Implement generic Android NFC card emulation, or a W3C Digital Credentials API integration, in a project with no Multipaz dependency and no Multipaz in scope.
- Expected skill behavior: recognize that the task is outside this skill, answer from general platform knowledge, and say plainly that no Multipaz guidance was applied.
- Required inspection steps: confirm the project has no `org.multipaz` dependency and that the user did not ask for Multipaz.
- Mistakes to avoid: injecting Multipaz modules, pinned sample paths, or Multipaz APIs into a non-Multipaz answer; claiming a pinned upstream path exists locally.
- Evidence of success: the answer contains no Multipaz-specific artifacts that the project does not use.
- Validation expectations: no Multipaz dependency, module, or sample path is introduced; if the project genuinely has Multipaz, the response says so instead.

## Required NFC Evaluation Behavior

Scenario: Add Multipaz NFC credential presentation to both Android and iOS.

- Expected behavior:
1. Inspect the Multipaz version and repository.
2. Explain that NFC presentation is currently supported only on Android.
3. Implement or propose the Android NFC portion using verified APIs and samples.
4. Do not generate iOS NFC implementation code.
5. Recommend a verified alternative for iOS.
6. Keep shared credential and protocol logic in common code only where appropriate.
7. Keep NFC transport or engagement logic in `androidMain`.
8. Clearly report the resulting platform capability difference.
- Failure conditions:
- claims iOS NFC support exists
- generates iOS NFC presentation code
- adds iOS NFC entitlements for the unsupported flow
- copies Android NFC code into `commonMain`
- implies Android and iOS have equivalent NFC support
