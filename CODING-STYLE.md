# Coding Style

Our project follows the [standard Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
with the following changes

- We allow up to 120 characters per line, please use all of the available space.

- Only capitalize the first letter of words/acronyms/abbreviations/initialisms when using
  CamelCasing for example it's `UiTest`, not `UITest`, `IsoMdocType`, not `ISOMDocType`,
  and so on.

- Avoid fully-qualified names in code; import classes, functions, and extension properties
  instead (e.g. `delay(...)` rather than `kotlinx.coroutines.delay(...)`). Never use star
  imports (`import package.*`).

- Always use `kotlin.time.Duration` and its extension properties (`10.milliseconds`,
  `5.seconds`, etc.) instead of raw numeric time values (such as `Long` milliseconds). In
  particular, call `delay(10.milliseconds)` and `withTimeout(5.seconds)` rather than
  `delay(10)` or `withTimeout(5000)`.

- Use **`keyBound`**, **`keyBindingRequired`**, **`KeyBound*`**, and **`Keyless*`** (e.g.
  `KeyBoundMdocCredential`, `KeylessSdJwtVcCredential`, `MdocDocumentType.keyBound`,
  `VerifiedPresentation.keyBound`, and `keyBindingRequired: Boolean = true`) when referring to
  whether a credential or presentation is cryptographically bound to a private key. Avoid terms
  such as `"holder binding"`, `holderBound`, `requireHolderBinding`,
  `requireCryptographicHolderBinding`, or `deviceBound` / `deviceBindingRequired` in APIs,
  parameters, properties, and KDoc—traditionally the *holder* refers to the human subject of the
  credential, whereas ISO mdoc `deviceSigned` and SD-JWT `kb+jwt` prove possession of a key.
  Reserve protocol-specific names strictly for wire-format serialization keys or direct wire
  structures (such as `"require_cryptographic_holder_binding"` in serialized DCQL JSON or
  `DocRequestInfo.nonKeyBoundSupported` in ISO/IEC 18013-5).

## Exception Handling

- **Never catch `Throwable`:** `Throwable` is the root of the hierarchy and includes fatal
  system errors (like `OutOfMemoryError`). Catching it prevents the environment from terminating
  when it absolutely needs to, leading to corrupted state and unpredictable behavior.

- **Don't throw `Error`:** The `java.lang.Error` (or `kotlin.Error` which is type-aliased for 
  when running on the JVM) class represents serious, unrecoverable problems that an application
  should generally not attempt to catch. Unlike standard exceptions, these typically indicate
  failures at the Java Virtual Machine (JVM) level or catastrophic system conditions. Use
  `IllegalStateException` or similar instead.

- **Avoid catching generic `Exception`:** Catching all exceptions acts as a black hole for
  standard developer bugs (like `NullPointerException` or `IllegalArgumentException`). This
  makes debugging extremely difficult because the application fails silently.

- **Catch specific exceptions:** Scope your `try-catch` blocks tightly and only catch the exact
  exceptions you anticipate and know how to recover from (e.g., `IOException` for network calls,
  or `JsException` for JavaScript interop boundaries).

- **Protect Coroutine Cancellation:** If you absolutely must catch a broad `Exception` (e.g., at a
  top-level boundary to prevent a crash), you **must** explicitly rethrow `CancellationException`.
  Failing to do so intercepts coroutine cancellation, breaking structured concurrency and causing
  memory leaks.
  ```kotlin
  catch (e: Exception) {
      if (e is CancellationException) throw e
      // Handle other exceptions safely
  }
  ```

* **Avoid standard `runCatching` with Coroutines:** Kotlin's built-in `runCatching {}` block
  catches `Throwable` under the hood. Do not use it around suspending functions unless you are
  using a custom wrapper that explicitly handles `CancellationException`.

* **Wrap `suspend` calls in `finally` blocks:** When a coroutine is cancelled, it throws
  a `CancellationException`. If you need to execute suspending cleanup code (like closing a
  connection or releasing a lock) inside a `finally` block, the coroutine is already in a
  cancelled state, and any standard `suspend` call will immediately fail. You must wrap the
  cleanup code in `withContext(NonCancellable)`.
  ```kotlin
  finally {
      withContext(NonCancellable) {
          // Suspending cleanup code goes here
      }
  }
  ```

* **Document exceptions in KDoc:** Every function or method must explicitly document the
  exceptions it throws using the `@throws` (or `@exception`) tag in its KDoc. This ensures that
  contributors and consumers of the Multipaz library know exactly what edge cases they are
  expected to handle. Also use the `@Throws` annotation on the function or method since this
  is required for error handling when consuming the API on e.g. iOS.

## Cryptography and Sensitive Information

* **Never use raw `ByteArray` for keys in public APIs:** Cryptographic keys must never be represented as raw
  `ByteArray` in public APIs. Symmetric cipher/MAC keys must use `SecretKey` (which inherits from `SecureByteString`),
  and asymmetric private keys must use `PrivateKey` (or its specific subclasses such as `EcPrivateKey`, `RsaPrivateKey`, etc.).
  Key agreement functions (`Crypto.keyAgreement`, `SecureArea.keyAgreement`, `AsymmetricKey.keyAgreement`) and KEM decapsulation
  (`Crypto.kemDecapsulate`, `SecureArea.kemDecapsulate`) must return `SecureByteString` (representing the raw shared secret)
  rather than raw `ByteArray` or `SecretKey`. Key derivation functions (`Hkdf.deriveKey`) accept `SecureByteString` as input
  keying material and return `SecretKey`. Do not add convenience overloads taking `ByteArray` for keys to public APIs
  (`Crypto.encrypt`, `Crypto.mac`, `Hkdf.deriveKey`, `Cose.coseMac0`, etc.).

* **Always clear memory using `AutoCloseable` / `use`:** `SecureByteString` (and its subclass `SecretKey`), as well as
  `PrivateKey`, implement `AutoCloseable` (with `close()` and alias `destroy()`). Whenever keys, shared secrets, or
  sensitive byte strings are created or used ephemerally, wrap them in Kotlin's `.use {}` block to ensure that the
  sensitive material is wiped from memory as soon as execution leaves the block, even if an exception or coroutine
  cancellation occurs:
  ```kotlin
  SecretKey(keyBytes).use { secretKey ->
      Crypto.encrypt(Algorithm.A128GCM, secretKey, nonce, plaintext)
  }
  ```
  Or chaining directly with `keyAgreement()`:
  ```kotlin
  keyAgreement(otherPublicKey).use { sharedSecret ->
      Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32).use { derivedKey ->
          // Use derivedKey
      }
  }
  ```
  If an object or service retains sensitive material long-term, the enclosing class should itself implement `AutoCloseable`
  and destroy the data when closed.

* **Explicitly zero raw byte arrays with `secureZero()`:** If raw key material or sensitive secrets exist in a `ByteArray`
  (e.g., read from storage, decrypted from a network payload, or derived before wrapping into `SecretKey` or `SecureByteString`),
  you must explicitly clear it using `ByteArray.secureZero()` in a `finally` block:
  ```kotlin
  val rawKeyBytes = readKeyFromStorage()
  try {
      SecretKey(rawKeyBytes).use { secretKey ->
          // Use secretKey
      }
  } finally {
      rawKeyBytes.secureZero()
  }
  ```
  **Never use `ByteArray.fill(0)`:** Standard array fills can be optimized away by the compiler or JVM/native JIT
  (dead store elimination). Always call `ByteArray.secureZero()`, which uses platform-specific primitives that
  guarantee memory writes are not eliminated.

* **Zero defensive copies returned by `encoded` or `toByteArray()`:** The `SecureByteString.encoded` / `toByteArray()`
  property returns a defensive copy of the underlying bytes. The caller is responsible for calling `secureZero()` on
  that copy once it is no longer needed. Internal SDK code should avoid `encoded` where possible, utilizing internal
  direct accessors or keeping the data within `SecureByteString`/`SecretKey`.

* **Avoid string representations of secrets:** Secrets (passwords, PINs, raw keys) should never be held in immutable
  `String` objects where they cannot be wiped from memory. Prefer `ByteArray` or `CharSequence` buffers that can be
  zeroed out immediately after use.

* **Random number generation and testability:** Functions and classes that generate random data
  (salts, IVs/nonces, tokens, challenges, key identifiers, session IDs, disclosure digests, etc.)
  must accept a `random: Random` parameter (or constructor parameter) of type `kotlin.random.Random`.
  The parameter must have a default value:
  - Default to `Crypto.secureRandom` if the generated data is security-sensitive (e.g. cryptographic
    keys, IVs, salts, nonces, session tokens, authorization codes, challenges, or privacy-preserving
    blinding factors).
  - Default to `Random.Default` only if the randomness is not security-sensitive (e.g. UI jitter,
    fuzz testing, or non-security tie-breaking).
  This design ensures that cryptographically secure randomness is always used by default in production,
  while unit and integration tests can inject a deterministically seeded `Random(seed)` instance to
  make cryptographic operations and protocol test vectors completely reproducible.

* **Secure default parameter and configuration values:** Default parameter values on functions,
  constructors, and data classes, as well as fallback values when parsing configuration files or JSON
  settings, must always be **secure by default**:
  - Never default security checks, signature verifications, trust-root validations, hardware security
    level requirements, or release-build checks to permissive or no-op values (such as `false`, `null`,
    or `{ true }` predicates that skip validation).
  - For validation and verification APIs where a caller-supplied key, predicate, or trust anchor is
    required for security (e.g. `issuerKey` or `checkNonce` in `SdJwt.verify` / `SdJwtKb.verify`),
    omit the default value entirely so callers must explicitly decide what to pass, rather than
    silently skipping verification when omitted.
  - For time-based expiration/validity checks (e.g. `now: Instant?`), default to `Clock.System.now()`
    rather than `null` so expiration is enforced unless the caller explicitly passes `null` to disable it.
  - For device attestation and hardware security parameters (e.g. `DeviceAttestationValidationData`,
    `validateAndroidKeyAttestation`, `CloudSecureAreaServer`), default to enforcing hardware-backed
    keys (`AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT`), verified boot (`GREEN`), GMS root CA
    attestation (`true`), and iOS release builds (`true`), with software attestation disabled (`false`).

## Security, Parsing, and Verification

### Untrusted Wire-Format Parsing and Resource Limits

* **Validate unsigned wire lengths in `Long`/`ULong` before casting to `Int`:** Accumulate and range-check
  multi-byte wire lengths (ASN.1, CBOR, etc.) against `0..remainingBytes` and `Int.MAX_VALUE` before calling
  `.toInt()`, computing `offset + length`, or allocating buffers.

* **Never rely on `IntRange` or `sliceArray` to catch negative lengths:** Inverted ranges (e.g.
  `start..(start - 1)`) silently return an empty array instead of throwing; always validate bounds explicitly.

* **Assert strict forward progress in parser loops:** Every `while (offset < end)` decode loop must assert
  `nextOffset > offset` before continuing so malformed lengths cannot spin infinitely into `OutOfMemoryError`.

* **Bound decompression across all peer inputs and KMP targets:** Never expose or invoke unbounded `inflate()` /
  `zlibInflate()` on untrusted data. Pass an explicit `maxSize` enforced incrementally while streaming across
  JVM/Android, iOS, and Web; close native/JDK `Inflater`/`Deflater` in `finally`; enforce aggregate limits
  across collections of compressed items (e.g. `otherDocuments`); and validate compression headers (such as JWE
  `zip`) before expensive cryptography.

* **Cap recursion depth in decoders and debug printers:** Enforce an explicit `MAX_DECODE_DEPTH` in every
  recursive decoder (`ASN1`, `Cbor`, nested containers) as well as recursive formatters (`ASN1.print`, diagnostic
  printers).

* **Use keyed hashing for maps keyed by untrusted values:** Hash-based collections populated from untrusted wire
  keys (such as `CborMap`) must use per-process keyed hashing (e.g. `CborHash` / SipHash seeded from
  `Crypto.secureRandom`) rather than predictable `ULong`/`String`/`ByteArray` `hashCode()`, with `hashCode()`
  matching `equals()` semantics (order-independence, canonical NaN and `-0.0`).

* **Do not rely on `catch (e: Exception)` for DoS containment:** `OutOfMemoryError` and `StackOverflowError` are
  `Error`s and intentionally bypass `catch (e: Exception)`—enforce size, depth, and loop bounds before allocation
  or recursion.

### Fail-Closed Verification and Strict Claim Parsing

* **Never commit fail-open security `TODO`s:** Never leave a `// TODO` stub returning `true` or skipping a
  security predicate (e.g. `checkAudience = { true } // TODO`). Unimplemented checks must fail closed
  (`throw UnsupportedOperationException` or reject) until implemented.

* **Optional or nullable context must fail closed:** Nullable verification parameters (e.g.
  `deviceRequest: DeviceRequest?`, `expectedAudience: String?`, `dcqlQuery: DcqlQuery?`) must never silently
  skip a security check when `null` (no `expected == null || expected == actual`). Reject payloads that require
  the missing context, or make the parameter non-nullable.

* **Never derive expected values from untrusted input:** Expected `aud`, `nonce`, `client_id`, `vct`, or `docType`
  must always originate from local verifier/wallet state or the original request—never fall back to reading the
  expected value from the untrusted presentation itself (`expectedAudience ?: sdJwtKb.jwtBody["aud"]`).

* **No permissive fallbacks in matching or dispatch:** Never fall back to catch-all candidate sets (e.g.
  `vctMatchingIndices.ifEmpty { sdJwtIndices }`) or negative-only guards ("reject only if another request names
  X"). Require an exact match or an explicit verifier-controlled allowlist (such as
  `DocumentTypeRepository.vctMatches()`).

* **Fail closed on out-of-bounds lookups and type-mismatched security claims:** Out-of-bounds status lookups
  (e.g. `StatusList.get(index)`) must throw or report unknown/invalid rather than returning `0` (`VALID`). Never
  use silent null-coercion (`.longOrNull`) on security claims (`exp`, `nbf`, `iat`) in a way that treats a
  present-but-non-integer value (e.g. RFC 7519 floating-point `NumericDate` such as `1.76E9`) as absent and skips
  validation; distinguish absent keys from present keys and handle or reject non-integer formats explicitly.

* **Reject unknown critical protocol extensions:** Always validate JOSE `crit` headers (RFC 7515 §4.1.11 /
  RFC 7516 §4.1.13) and reject malformed or unsupported critical extensions across every JWS/JWE verification and
  decryption path.

### Constraint Enforcement, Authorization, and Identity Binding

* **Every parsed or persisted security field must have an enforcement site:** Do not parse or store security
  constraints (`notAfter` on VICAL/RICAL, `isTrustAnchor`, `IssuanceState.configurationId`/`scope`, DCQL
  `vct_values`/`doctype_value`/`claims`/`claim_sets`/`multiple`/`credential_sets`, SD-JWT `cnf`/`nbf`/`exp`,
  launch URI `client_id`) without verifying they are enforced on every consumption path.

* **Validate identity bindings centrally in constructors or factory methods:** Do not model security-bound pairs
  (like `OpenID4VPRequesterIdentity(certChain, clientId)`) as plain unvalidated data classes constructed across
  multiple call sites. Enforce prefix-to-certificate bindings (`x509_san_dns:` vs. leaf dNSName SAN, `x509_hash:`
  vs. leaf DER SHA-256, launch URI vs. Request Object `client_id`, and untrusted `redirect_uri`/`response_uri`
  FQDN fallback) centrally so no call site can omit them.

* **Separate authentication from authorization on servers:** Verifying an access token and DPoP proof proves
  authentication only. Always assert that caller-supplied identifiers (`credential_configuration_id`, `format`)
  match the session's persisted authorization (`state.configurationId` / `state.scope`) before fetching personal
  data or minting credentials, and enforce least-privilege scoping in backend data services (do not
  unconditionally return `core` identity attributes for non-core scopes).

* **Treat peer-supplied IDs as untrusted constraint selectors, not proof of fulfillment:** Cryptographic validity
  proves *who signed* and *freshness*, not *query fulfillment*. After verifying signatures, enforce that the
  verified credential's format, signed `vct`/`docType`, disclosed claims, `claim_sets`, `multiple`, and
  `credential_sets` satisfy the original query, and reject empty or partial responses.

* **Prevent proof-of-possession downgrade:** When a credential carries key-binding material (such as SD-JWT `cnf`
  or mdoc `deviceKeyInfo`), require the corresponding holder proof (`kb+jwt` or `deviceSigned`) and reject
  bare/keyless presentations.

### Provenance Separation, Consent Parity, and Session Binding

* **Strictly separate `issuerSignedClaims` and `deviceSignedClaims`:** Never add holder-signed data (SD-JWT
  KB-JWT claims, mdoc/ZK `deviceSigned` elements) into `issuerSignedClaims`, never allow holder claims to
  overwrite issuer claims in claim maps or satisfy issuer-only DCQL queries, and enforce MSO `keyAuthorizations`
  on every device-signed element.

* **Maintain a single source of truth from Consent to Wire Disclosure to Audit Log:** Derive the claims shown in
  the consent UI, the claims filtered and serialized into the wire response (mdoc, SD-JWT VC, ZK, `chv_1`), and
  the claims recorded in `EventPresentmentData` from the exact same approved match object (`match.claims`), never
  re-reading raw `DcqlCredentialQuery.claims`.

* **Bind cached consent, local auth, and pending responses to verified peer identity:** Across retries,
  reconnects, or NFC retaps, never equate requests by structural shape alone (`isStructurallyEquivalent`).
  Re-run peer authentication (`verifyReaderAuthentication()`), verify certificate chain and trust identity
  consistency, cancel or re-prompt on mismatch, and record the actual verified recipient in the event log.

* **Never strip unvalidated security claims from API outputs:** Server and facade layers must enforce validity
  (`exp`, `nbf`, `notAfter`) before stripping standard metadata claims from processed responses.

### Deprecated API Hygiene and Security KDoc Accuracy

* **Never leave deprecated security-sensitive APIs unpatched:** When adding a security check to a replacement API
  (such as `DeviceResponse.verify()`), remove the deprecated counterpart (`DeviceResponseParser`), delegate it to
  the fixed implementation, or backport the check in the same change—never ship a vulnerable deprecated API with a
  generic deprecation message.

* **Keep KDoc security contracts accurate:** Never state in KDoc that a method verifies trust chains, audiences,
  or performs "the same checks as X" unless every code path and parameter combination enforces it. When checks
  such as trust-chain validation are the caller's responsibility (e.g. `PresentmentRecord.verify()`), state that
  explicitly in KDoc.

## Java / JVM Target Versions

* **JDK 21 Toolchain for JVM, Java 17 for Android:** The build uses `jvmToolchain(21)` across all
  Gradle modules so `jvmMain` and all JVM server/CLI modules target **JVM 21** (required by
  JVM-only server dependencies such as `com.android.keyattestation:keyattestation`). However, all
  Android library and application targets (`androidTarget` and `android { compileOptions }`) must
  explicitly remain at **`JvmTarget.JVM_17`** and **`JavaVersion.VERSION_17`** so that published
  Android AARs remain drop-in compatible with Android applications targeting Java 17.

* **Avoid Java 21-only APIs in Android-shared source sets:** Because Gradle compiles with JDK 21,
  code in `commonMain`, `javaSharedMain`, and `androidMain` must not invoke JDK 21-only APIs that
  are absent on Android / Java 17 (for example `java.util.SequencedCollection` methods such as
  `getFirst()` / `getLast()` on Java collections—use Kotlin's `.first()` / `.last()` extensions
  instead).

## UI Golden Screenshots (`multipaz-compose`)

* **Consent Prompt Golden Suite & Visual Catalog:** The `Consent` composable and related presentment
  UI (`X509CertViewer`, `RenderClaimValue`, purpose/risk classification, localization, RTL, and dark
  mode) are verified against a Robolectric-based golden-master screenshot suite in
  `multipaz-compose/src/androidUnitTest/kotlin/org/multipaz/compose/presentment/ConsentGoldenScreenshotTest.kt`,
  with reference PNGs and a browsable visual catalog in
  [`multipaz-compose/golden-screenshots/README.md`](multipaz-compose/golden-screenshots/README.md).

* **Updating Golden Screenshots:** Whenever a change intentionally alters the consent prompt UI,
  layout, styling, or string resources (`values/strings.xml`, `values-da/strings.xml`,
  `values-ar/strings.xml`), regenerate the golden PNGs and `README.md` catalog by passing
  `-PupdateGoldens=true`, and inspect the resulting image diffs before committing:
  ```bash
  ./gradlew :multipaz-compose:testDebugUnitTest \
    --tests "org.multipaz.compose.presentment.ConsentGoldenScreenshotTest" \
    -PupdateGoldens=true --rerun-tasks -Pdisable.web.targets=true
  ```


