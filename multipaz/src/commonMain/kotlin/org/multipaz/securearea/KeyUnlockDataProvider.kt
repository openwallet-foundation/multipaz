package org.multipaz.securearea

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.multipaz.prompt.PromptModel

/**
 * An object that provides [KeyUnlockData] when a key is [SecureArea] is locked.
 *
 * When [SecureArea.sign] or [SecureArea.keyAgreement] call is made and the key is locked,
 * current [coroutineContext] is queried for this interface using [KeyUnlockDataProvider.Key].
 * If it is found, [getKeyUnlockData] is used to obtain [KeyUnlockData] to unlock the key and
 * the operation (signing or key agreement) is retried.
 *
 * Note: there is no further retries if returned [KeyUnlockData] fails to unlock the key. This is
 * so non-interactive [KeyUnlockDataProvider] implementations do not have to deal with retry logic
 * if the unlock data fails to unlock the key. If retry is necessary, it should be implemented
 * inside by [KeyUnlockDataProvider] itself (which may be [SecureArea]-specific).
 *
 * If [KeyUnlockDataProvider] is not found, [SecureArea] can use its own default implementation
 * that requires [PromptModel] in the current coroutine context. Use [PromptModel.toHumanReadable]
 * to customize text that is used in the prompts.
 */
interface KeyUnlockDataProvider : CoroutineContext.Element {
    object Key: CoroutineContext.Key<KeyUnlockDataProvider>

    override val key: CoroutineContext.Key<KeyUnlockDataProvider>
        get() = Key

    /**
     * Provides [KeyUnlockData] for a [SecureArea]-key-based operation.
     *
     * @param secureArea Secure Area where the locked key resides
     * @param alias locked key's alias
     * @param unlockReason conveys the reason for the operation (e.g. credential presentment)
     */
    suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        unlockReason: UnlockReason
    ): KeyUnlockData
}