# Dependency Guide

## Inspect before editing

- Check whether the project uses `gradle/libs.versions.toml`, direct strings, or convention plugins.
- Prefer the project's existing style.
- Search the target project's Gradle files, version catalogs, and build logic for `org.multipaz`, `multipaz`, and referenced dependency aliases. For example, from the project root: `rg -n "org[.]multipaz|multipaz" -g "*.gradle" -g "*.gradle.kts" -g "*.toml" -g "*.properties" -g "*.kt" -g "!**/build/**" -g "!**/.gradle/**" .`. Follow aliases and version definitions before judging whether versions are aligned.

## Pinned upstream evidence

- The following paths are from the upstream commit recorded in
  [upstream-source.md](upstream-source.md), not from every target project.
- Upstream versions are centralized in `gradle/libs.versions.toml`.
- Internal samples usually depend on local projects such as `project(":multipaz")`, `project(":multipaz-compose")`, `project(":multipaz-dcapi")`, and `project(":multipaz-openid4vci")`.
- `samples/testapp/build.gradle.kts` shows a broad holder app stack.
- `samples/SwiftTestApp/build.gradle.kts` shows the iOS XCFramework export surface.

## Typical module choices

- Core holder, verifier, storage, trust, mdoc, SD-JWT VC: `multipaz`
- Compose UI, QR scanning, Android presentment and NFC service wrappers: `multipaz-compose`
- Standard credential types and canned requests: `multipaz-doctypes`
- Example Utopia types and request sets: `multipaz-utopia`
- Digital Credentials API integration: `multipaz-dcapi`
- OpenID4VCI wallet-side provisioning APIs used by holder apps are in `multipaz`; `multipaz-openid4vci` is for issuer/server-side integrations and should not be added to a shared mobile holder unless current samples prove it is needed.
- ZKP: `multipaz-longfellow`
- SwiftUI-facing reusable UI: `multipaz-swiftui`

## Dependency rules

- Add only the modules needed for the requested workflow.
- Keep versions aligned across all `org.multipaz:*` coordinates in consumer projects.
- For Compose holder QR presentment, add `org.multipaz:multipaz-compose` alongside `org.multipaz:multipaz`; keep the version aligned and verify the Android artifact's manifest/minSdk requirements.
- If shared holder code creates a Ktor `HttpClient`, add platform engine dependencies such as `io.ktor:ktor-client-android` for Android and `io.ktor:ktor-client-darwin` for iOS. Prefer explicit platform factories over bare `HttpClient()` so missing engines are caught during compilation.
- Do not replace project dependencies with Maven artifacts inside this repository.
- Do not upgrade unrelated Kotlin, AGP, Compose, or iOS toolchain versions unless the task explicitly includes upgrade work.

## Validation

- Re-read changed declarations and confirm their versions and source-set placement. If resolved versions are unclear, use the affected module's `dependencies` or `dependencyInsight` task with a configuration verified to exist. Validate changes with the affected module's actual compile and test tasks.
- If a feature request depends on `multipaz-compose` or `multipaz-dcapi`, verify the platform-specific dependencies and activities from the sample before adding them.
