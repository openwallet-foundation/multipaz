package org.multipaz.context

/**
 * Abstract base class for holding UI-relevant data that may be accessed by a coroutine.
 *
 * Certain platform APIs (such as [org.multipaz.nfc.NfcTagReader.scan] and
 * [org.multipaz.digitalcredentials.DigitalCredentials.request]) require access to the currently
 * active UI host (for example, an `Activity` on Android) from within a suspending function.
 *
 * ### Usage in Compose Multiplatform
 *
 * If using `multipaz-compose`, use `org.multipaz.compose.rememberUiBoundCoroutineScope` to obtain
 * a `CoroutineScope` that automatically binds the platform UI context (and optionally a
 * [org.multipaz.prompt.PromptModel] for showing prompt dialogs):
 *
 * ```kotlin
 * val coroutineScope = rememberUiBoundCoroutineScope { promptModel }
 *
 * Button(
 *     onClick = {
 *         coroutineScope.launch {
 *             val result = nfcTagReader.scan(message = "Hold near tag") { tag ->
 *                 // interact with tag
 *             }
 *         }
 *     }
 * ) {
 *     Text("Scan")
 * }
 * ```
 *
 * ### Usage outside Compose (Android)
 *
 * On Android, when not using Compose (for example, from an `Activity`, `Fragment`, or view-based
 * code), include `org.multipaz.context.AndroidUiContext` (wrapping an `Activity` or a `Context`
 * backed by an `Activity`) in the coroutine context using `withContext` or `launch`:
 *
 * ```kotlin
 * lifecycleScope.launch(AndroidUiContext(activity) + promptModel) {
 *     val result = nfcTagReader.scan(message = "Hold near tag") { tag ->
 *         // interact with tag
 *     }
 * }
 * ```
 *
 * On iOS and other platforms where system UI sheets do not require an explicit activity reference,
 * no `UiContext` element needs to be added to the coroutine context.
 */
abstract class UiContext
