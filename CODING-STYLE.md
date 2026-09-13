# Coding Style

Our project follows the [standard Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
with the following changes

- We allow up to 120 characters per line, please use all of the available space.

- Only capitalize the first letter of words/acronyms/abbreviations/initialisms when using
  CamelCasing for example it's `UiTest`, not `UITest`, `IsoMdocType`, not `ISOMDocType`,
  and so on.

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

