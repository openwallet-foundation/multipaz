# Project Inspection

## First pass

From the target project root, locate the build and platform configuration files. For example:

```bash
rg --files --hidden -g "!.git/**" -g "!**/build/**" -g "!**/.gradle/**" -g "!**/node_modules/**" -g "settings.gradle*" -g "build.gradle*" -g "gradle-wrapper.properties" -g "*.versions.toml" -g "gradle.properties" -g "AndroidManifest.xml" -g "*Info.plist" -g "*.entitlements" -g "project.pbxproj"
```

Read the relevant files and follow custom version catalogs or included build logic referenced by settings. Record file evidence for:

- Gradle wrapper version
- Kotlin, AGP, Compose, and Multipaz versions when discoverable
- KMP targets and modules
- source sets
- version catalogs and included builds
- existing Multipaz dependencies
- Android manifests, iOS plist files, and entitlements
- Android NFC declarations
- suspicious iOS NFC-related configuration that does not imply support

## Follow-up

- Read `settings.gradle.kts` or `settings.gradle` to verify included modules, custom version catalogs, and composite builds.
- Read the target module `build.gradle.kts` or `build.gradle` files and referenced convention plugins to see whether the app already exports iOS frameworks, uses Compose, or depends on `multipaz-dcapi`.
- Check whether the app already has holder, verifier, or issuer code paths.

## Classification hints

- Holder app: document store, secure area, provisioning, consent, or presentment source logic.
- Verifier app: request construction, OpenID4VP, DCQL, or device retrieval.
- Issuer/provisioning: OpenID4VCI, attestation, redirect handling, backend RPC.
- Platform work: manifests, activities, entitlements, `iosMain`, or native SwiftUI integration.

## NFC-specific inspection

- Android: verify `android.permission.NFC`, `android.hardware.nfc`, `android.permission.BIND_NFC_SERVICE`, and APDU services.
- iOS: treat any `CoreNFC` import or entitlement as a warning signal only. It does not prove Multipaz iOS NFC presentment support.
