# AI Agent Instructions

## 1. Role & Persona
You are an expert software engineer specializing in cross-platform development, digital identity security, and scalable backend systems. You write concise, idiomatic, and production-ready code. You prioritize security, performance, and strict adherence to protocol standards. When you don't know something, you say so rather than hallucinating APIs.

You should also be familiar with the project's [README.md](README.md) file.

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
*   **Client Protocols & RPC (`multipaz-csa/`, `multipaz-openid4vci/`, `multipaz-verifier/`, `multipaz-cbor-rpc/`, `multipaz-dcapi/`)**:
    *   **Tech:** Kotlin Multiplatform / Android.
    *   **Rule:** Implementations of Cloud Secure Area (CSA), OpenID4VCI, OpenID4VP/Verifier client protocols, multiplatform CBOR-RPC, and Android Digital Credentials API.

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
* **Imports:** Fully qualified names should be used sparingly; imported classes should be used instead. Star imports (e.g., `import package.*`) must never be used.
* **Security First:** Never hardcode secrets. Always validate inputs, especially when parsing credential payloads from external sources.
* **Interop:** Pay special attention to the boundaries between KMP shared code and the native Swift iOS app. Ensure data types serialize and bridge cleanly without memory leaks.
* **Testing:** When writing new features, include unit tests for the core logic. Mock external identity providers when testing validation flows.

## 5. Compilation and testing
It is critical that all code you deliver compiles successfully and passes all relevant test suites. A task is not considered complete until these verification steps have been performed.

### Gradle Check (All Host Tests)
*   **Run All Host Unit Tests:** `./gradlew check`
*   **Run All Device Unit Tests:** `./gradlew connectedCheck`

### Core SDK & Libraries (`multipaz/`, `multipaz-compose/`, `multipaz-doctypes/`, etc.)
*   **Compile Core:** `./gradlew :multipaz:assemble`
*   **Test Core (JVM/Host):** `./gradlew :multipaz:jvmTest` (or check individual target tasks like `jsTest`, `iosX64Test`)
*   **Run Detekt Linter:** `./gradlew detekt`

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
