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
  expected to handle.
