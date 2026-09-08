package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.Reason
import kotlin.coroutines.CoroutineContext

/**
 * A [KeyUnlockDataProvider] implementation holding preloaded [KeyUnlockData] mapped by key alias and [SecureArea].
 *
 * @param map map of (secureAreaIdentifier, alias) to [KeyUnlockData].
 * @param fallbackProvider optional provider to delegate to if a requested key is not in [map].
 */
class PreloadedKeyUnlockDataProvider private constructor(
    private val map: Map<Pair<String, String>, KeyUnlockData>,
    private val fallbackProvider: KeyUnlockDataProvider? = null
) : KeyUnlockDataProvider {

    override val key: CoroutineContext.Key<KeyUnlockDataProvider>
        get() = KeyUnlockDataProvider.Key

    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        algorithm: Algorithm,
        unlockReason: Reason
    ): KeyUnlockData {
        val data = map[Pair(secureArea.identifier, alias)]
        if (data != null) {
            return data
        }
        if (fallbackProvider != null) {
            return fallbackProvider.getKeyUnlockData(secureArea, alias, algorithm, unlockReason)
        }
        throw KeyLockedException(
            "No preloaded KeyUnlockData for key '$alias' in Secure Area '${secureArea.identifier}'"
        )
    }

    /**
     * Builder for [PreloadedKeyUnlockDataProvider].
     */
    class Builder {
        private val map = mutableMapOf<Pair<String, String>, KeyUnlockData>()

        /**
         * Adds a preloaded [KeyUnlockData].
         *
         * If [keyUnlockData] is `null` (e.g. when user authentication is not required),
         * this method is a no-op.
         *
         * @param keyUnlockData the preloaded unlock data, or `null`.
         * @return this builder.
         */
        fun add(keyUnlockData: KeyUnlockData?): Builder {
            if (keyUnlockData != null) {
                map[Pair(keyUnlockData.secureArea.identifier, keyUnlockData.alias)] = keyUnlockData
            }
            return this
        }

        /**
         * Adds a list of preloaded [KeyUnlockData] items.
         *
         * @param keyUnlockDataList the list of preloaded unlock data items.
         * @return this builder.
         */
        fun add(keyUnlockDataList: List<KeyUnlockData>): Builder {
            for (keyUnlockData in keyUnlockDataList) {
                add(keyUnlockData)
            }
            return this
        }

        /**
         * Adds a preloaded [KeyUnlockData] for a specific key in a [SecureArea].
         *
         * If [keyUnlockData] is `null` (e.g. when user authentication is not required),
         * this method is a no-op.
         *
         * @param secureArea the Secure Area containing the key.
         * @param alias the alias of the key.
         * @param keyUnlockData the preloaded unlock data, or `null`.
         * @return this builder.
         */
        fun add(secureArea: SecureArea, alias: String, keyUnlockData: KeyUnlockData?): Builder {
            if (keyUnlockData != null) {
                map[Pair(secureArea.identifier, alias)] = keyUnlockData
            }
            return this
        }

        /**
         * Adds a list of preloaded [KeyUnlockData] items for a specific key in a [SecureArea].
         *
         * @param secureArea the Secure Area containing the key.
         * @param alias the alias of the key.
         * @param keyUnlockDataList the list of preloaded unlock data items.
         * @return this builder.
         */
        fun add(secureArea: SecureArea, alias: String, keyUnlockDataList: List<KeyUnlockData>): Builder {
            for (keyUnlockData in keyUnlockDataList) {
                add(keyUnlockData)
            }
            return this
        }

        /**
         * Builds a [PreloadedKeyUnlockDataProvider].
         *
         * @param fallbackProvider optional fallback provider.
         * @return a new [PreloadedKeyUnlockDataProvider].
         */
        fun build(fallbackProvider: KeyUnlockDataProvider? = null): PreloadedKeyUnlockDataProvider {
            return PreloadedKeyUnlockDataProvider(map.toMap(), fallbackProvider)
        }
    }
}

/**
 * Builds a [PreloadedKeyUnlockDataProvider] using DSL syntax.
 *
 * @param builderAction action to configure the builder.
 * @return a new [PreloadedKeyUnlockDataProvider].
 */
inline fun buildPreloadedKeyUnlockDataProvider(
    builderAction: PreloadedKeyUnlockDataProvider.Builder.() -> Unit
): PreloadedKeyUnlockDataProvider {
    val builder = PreloadedKeyUnlockDataProvider.Builder()
    builder.builderAction()
    return builder.build()
}
