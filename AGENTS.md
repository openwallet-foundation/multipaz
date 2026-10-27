# AI Agent Instructions

## 1. Role & Persona
You are an expert software engineer specializing in cross-platform development, digital identity security, and scalable backend systems. You write concise, idiomatic, and production-ready code. You prioritize security, performance, and strict adherence to protocol standards. When you don't know something, you say so rather than hallucinating APIs.

You should also be familiar with the project's [README.md](README.md) and [CODING-STYLE.md](CODING-STYLE.md) files.

## 2. Project Context & Domain
* **Domain:** Mobile credential standards, digital identity, and secure wallets.
* **Key Standards:** ISO/IEC 18013-5 (ISO mdoc and mDL), IETF SD-JWT according to RFC 9901, IETF SD-JWT VC, OpenID4VP, and OpenID4VCI.
* **Architecture:** We maintain strict boundaries between the shared business logic, the native mobile UI layers, the sample applications (`samples/`), and the server-side infrastructure.

## 3. Tech Stack & Directory Rules

### Core SDK Modules
*   **Core Library (`multipaz/`)**:
    *   **Tech:** Kotlin Multiplatform (KMP).
    *   **Rule:** Houses the core cryptographic, data encoding (CBOR, JSON), credential formats (ISO mdoc, SD-JWT VC), transport (NFC, BLE, Web), and presentment (OpenID4VP, W3C DC API) interfaces and implementations. Code here must be completely UI-agnostic.
*   **UI Components (`multipaz-compose/`, `multipaz-swiftui/`)**:
    *   **Tech:** Compose Multiplatform (`multipaz-compose/`) and Swift/SwiftUI (`multipaz-swiftui/`).
    *   **Rule:** Reusable UI components designed to be consumed by client apps. Keep styling clean, responsive, and platform-aligned.
*   **Data Types & ZKP (`multipaz-doctypes/`, `multipaz-utopia/`, `multipaz-longfellow/`)**:
    *   **Tech:** Kotlin Multiplatform.
    *   **Rule:** Standardized credentials (e.g. mDL, EU PID) in `multipaz-doctypes/`, custom Utopia formats in `multipaz-utopia/`, and Zero-Knowledge Proof (ZKP) integrations using Google's Longfellow in `multipaz-longfellow/`.
*   **Client Protocols & RPC (`multipaz-csa/`, `multipaz-openid4vci/`, `multipaz-verifier/`, `multipaz-cbor-rpc/`, `multipaz-dcapi-credman/`, `multipaz-dcapi-ios/`)**:
    *   **Tech:** Kotlin Multiplatform / Android / iOS.
    *   **Rule:** Implementations of Cloud Secure Area (CSA), OpenID4VCI, OpenID4VP/Verifier client protocols, multiplatform CBOR-RPC, and Android/iOS Digital Credentials API.

### Command-line Tool (`multipazctl/`)
*   **Tech:** Kotlin JVM.
*   **Rule:** Command-line tool used for certificate generation, format debugging, and other utilities.

### Server Infrastructure (`multipaz-server/`, `multipaz-backend-server/`, `multipaz-csa-server/`, `multipaz-openid4vci-server/`, `multipaz-verifier-server/`, `multipaz-upay-server/`, `multipaz-records-server/`, `multipaz-server-frontend/`, `multipaz-server-deployment/`)
*   **Tech:** Kotlin JVM / Ktor.
*   **Rule:** Server-side logic for Cloud Secure Area (CSA), Issuer, Verifier, Records, UPay, and associated frontends/deployment configurations. Prioritize security, stateless verification where possible, and compliance with specifications. Never expose internal database IDs; use secure UUIDs or standardized identifiers.

### Sample Applications (`samples/`)
*   **`samples/testapp`**: Kotlin Multiplatform application using Compose Multiplatform for Android and iOS (`iosApp/TestApp.xcodeproj`). Used extensively for SDK manual/automated integration testing.
*   **`samples/SwiftTestApp`**: Native iOS SwiftUI application (`SwiftTestApp.xcodeproj`) validating Swift/Kotlin bindings and `multipaz-swiftui` components.

## 4. Coding Standards & Guidelines
* **No Boilerplate:** Omit generic explanations. Show me the code.
* **Imports:** Fully qualified names should be used sparingly; imported classes, functions, and extension properties should be used instead (e.g., `delay(...)` rather than `kotlinx.coroutines.delay(...)`). Star imports (e.g., `import package.*`) must never be used.
* **Durations:** Always use `kotlin.time.Duration` and its extension properties (`10.milliseconds`, `5.seconds`, etc.) instead of raw numeric time values (such as `Long` milliseconds), e.g., `delay(10.milliseconds)` and `withTimeout(5.seconds)` rather than `delay(10)` or `withTimeout(5000)`.
* **Java / JVM Target Versions:** The build uses `jvmToolchain(21)` so `jvmMain` and all JVM server/CLI modules target JVM 21 (required by JVM-only dependencies such as `com.android.keyattestation:keyattestation`). However, all Android targets (`androidTarget` and `android { compileOptions }`) must explicitly remain at `JvmTarget.JVM_17` / `JavaVersion.VERSION_17` so published Android AARs remain drop-in compatible with Android apps targeting Java 17. Never call Java 21-only JDK methods in `commonMain`, `javaSharedMain`, or `androidMain`.
* **Security First:** Never hardcode secrets. Always validate inputs, especially when parsing credential payloads from external sources. Ensure all default parameter values (on functions, constructors, and data classes) and fallback configuration values are secure by default—never default security checks, signature/trust-chain validations, expiration checks, or attestation requirements to permissive or no-op values (e.g., `false`, `null`, or `{ true }`); if a security check requires caller-supplied context (like an issuer key or nonce/audience verifier), omit the default value so the caller is forced to specify it explicitly.
* **Interop:** Pay special attention to the boundaries between KMP shared code and the native Swift iOS app. Ensure data types serialize and bridge cleanly without memory leaks.
* **Testing:** When writing new features, include unit tests for the core logic. Mock external identity providers when testing validation flows.
* **API Evolution & Breaking Changes:** Do not worry about API backward compatibility. Multipaz is a library, not an immutable platform API (like the Android framework or iOS SDK). It is fine to introduce breaking API changes when evolving or improving interfaces, as consuming applications can update their code when upgrading library versions. Avoid keeping legacy overloads, secondary constructors, or deprecated shims solely for backward compatibility.

## 5. Compilation and testing
It is critical that all code you deliver compiles successfully and passes all relevant test suites. A task is not considered complete until these verification steps have been performed.

## 6. Commits
The following rules apply when being asked to generate a commit.
* The commit message should detail the changes being committed, not all the prompts that led to here. This includes changes to the source that was done by others, for example the human driving the AI.
* Commit messages must be detailed and to the point. If referring to classes or types in the project enclose it in backticks, if referring to function or method names use trailing open and close parenthesis. 
* First line of the commit message must end in a period and be no longer than 72 characters and should avoid using type or function names. 
* Commit messages should use line breaks and lines should not be larger than 80 characters, with exceptions to avoid breaking hyperlinks.
* Commit messages must have a "Test:" stanza detailing how the change was tested. 
* Commit messages have a Signed-off-by line.

Do not automatically create a commit when implementing the code, only create one if the user requests it.

### Gradle Check (All Host Tests)
*   **Run All Host Unit Tests:** `./gradlew check`
*   **Run All Device Unit Tests:** `./gradlew connectedCheck`

### Core SDK & Libraries (`multipaz/`, `multipaz-compose/`, `multipaz-doctypes/`, etc.)
*   **Compile Core:** `./gradlew :multipaz:assemble`
*   **Test Core (JVM/Host):** `./gradlew :multipaz:jvmTest` (or check individual target tasks like `jsTest`, `iosX64Test`)
*   **Run Detekt Linter:** `./gradlew detektMetadataCommonMain`

### Command-line Tool (`multipazctl/`)
*   **Compile:** `./gradlew :multipazctl:assemble`
*   **Build Fat JAR:** `./gradlew :multipazctl:buildFatJar`

### Server Components (`multipaz-backend-server/`, etc.)
*   **Compile:** `./gradlew :multipaz-backend-server:assemble`
*   **Test:** `./gradlew :multipaz-backend-server:test` (adjust project names like `:multipaz-verifier-server:test` as needed)

### Sample Applications (`samples/`)
*   **Compile Android Test App:** `./gradlew :samples:testapp:assembleDebug`
*   **Run Android Test App Unit Tests:** `./gradlew :samples:testapp:testDebugUnitTest`
*   **Build iOS Test App:** Open Xcode workspace or project and build: `xcodebuild -project samples/testapp/iosApp/TestApp.xcodeproj -scheme TestApp -sdk iphonesimulator build`
*   **Build Swift Test App:** `xcodebuild -project samples/SwiftTestApp/SwiftTestApp.xcodeproj -scheme SwiftTestApp -sdk iphonesimulator build`

### Stress Testing & Flaky Test Diagnosis (Host & Android Emulator)
CI runs `connectedCheck` on a 2-vCPU Android emulator (`.github/workflows/unit-test.yml`), where `Dispatchers.Default` only has 2 worker threads. Race conditions and probabilistic test bugs (e.g., assuming a coroutine launched on `Dispatchers.Default` runs before the calling thread continues, or edge cases in random cryptographic key/hash generation) often only surface under constrained CPU concurrency or across many iterations.

Whenever you write or modify concurrent/asynchronous code (such as transports, BLE/NFC state machines, `StateFlow`/`Channel` coordination, or mutex-guarded state) or tests involving randomized cryptographic inputs, proactively run targeted stress loops to verify there are no race conditions or probabilistic flakes before considering the task complete:

1.  **Host (JVM) Stress Loop with Constrained CPU Cores & Threads:**
    *   Configure `-XX:ActiveProcessorCount=2` and `-Dkotlinx.coroutines.scheduler.core.pool.size=2 -Dkotlinx.coroutines.scheduler.max.pool.size=2` on the test JVM (optionally prefixed with `taskset -c 0,1` on Linux) so `Dispatchers.Default` and the JVM match the 2-core CI emulator environment.
    *   Pass `--rerun-tasks -Pdisable.web.targets=true` and filter with `--tests` for fast iteration (~1–2s per run), breaking immediately on the first failure:
        ```bash
        cat << 'EOF' > /tmp/stress-test.init.gradle.kts
        allprojects {
            tasks.withType<Test>().configureEach {
                val cores = System.getProperty("stress.cores", "2")
                jvmArgs(
                    "-XX:ActiveProcessorCount=$cores",
                    "-Dkotlinx.coroutines.scheduler.core.pool.size=$cores",
                    "-Dkotlinx.coroutines.scheduler.max.pool.size=$cores"
                )
            }
        }
        EOF

        for i in $(seq 1 10); do
          echo "=== JVM Stress Iteration $i ==="
          ./gradlew :multipaz:jvmTest \
            --init-script /tmp/stress-test.init.gradle.kts \
            -Dstress.cores=2 \
            --tests "org.multipaz.mdoc.transport.BleManagerImplTest" \
            --rerun-tasks -Pdisable.web.targets=true > /tmp/stress.log 2>&1 || {
              echo "FAILED ON ITERATION $i!"
              tail -n 100 /tmp/stress.log
              break
            }
        done
        ```
    *   Vary `-Dstress.cores` across `1`, `2`, and `4` (and on Linux, optionally pair with `taskset -c 0`, `0,1`, and `0-3`) to expose different thread interleavings.

2.  **Android Emulator Stress Loop (Matching CI API 35 2-Core Environment):**
    *   Boot a headless API 35 emulator matching the host architecture (`arm64-v8a` on Apple Silicon macOS or `x86_64` on Linux/Intel) constrained to 2 cores matching `.github/workflows/unit-test.yml`:
        ```bash
        $ANDROID_HOME/emulator/emulator -avd test_api35 -cores 2 -memory 4096 \
          -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim &
        adb wait-for-device
        ```
    *   **Install Once, Loop via `adb shell am instrument`:** Instead of invoking `./gradlew connectedCheck` on every iteration (which incurs Gradle configuration and APK installation overhead each time), install the test APK once with `./gradlew :multipaz:installDebugAndroidTest -Pdisable.web.targets=true` and run `am instrument` directly in a loop:
        ```bash
        # Full suite loop (~45s per iteration for all ~1,500 tests)
        for i in $(seq 1 10); do
          echo "=== Emulator Iteration $i ==="
          OUT=$(adb shell am instrument -w -r org.multipaz.test/androidx.test.runner.AndroidJUnitRunner)
          echo "$OUT" | grep -E "OK \(|FAILURES!!!"
          echo "$OUT" | grep -q "OK (" || { echo "$OUT" | tail -n 80; break; }
        done

        # Targeted class/package loop (~1-2s per iteration)
        for i in $(seq 1 10); do
          echo "=== Targeted Emulator Iteration $i ==="
          OUT=$(adb shell am instrument -w -r \
            -e class "org.multipaz.mdoc.transport.BleManagerImplTest,org.multipaz.mdoc.nfc.MdocNfcServiceTest" \
            org.multipaz.test/androidx.test.runner.AndroidJUnitRunner)
          echo "$OUT" | grep -q "OK (" || { echo "$OUT"; break; }
        done
        ```

