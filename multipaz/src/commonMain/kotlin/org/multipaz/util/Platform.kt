@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.util

import io.ktor.client.engine.HttpClientEngineFactory
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage

/**
 * Object for selecting platform specific functionality.
 */
expect object Platform {
    /**
     * The name and version of the platform.
     *
     * @throws NotImplementedError if called on a platform which isn't Android or iOS.
     */
    val name: String

    /**
     * The version of the Multipaz library, for example `0.96.0` or `0.97.0-pre.1.aaf8d71e`.
     *
     * This string adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
     */
    val version: String

    /**
     * A [PromptModel] implementation suitable for the platform.
     */
    val promptModel: PromptModel

    /**
     * A [Storage] instance suitable for the platform.
     *
     * @throws NotImplementedError if called on a platform which isn't Android or iOS.
     */
    val storage: Storage

    /**
     * A [Storage] instance suitable for the platform in a location where the
     * underlying data file is excluded from backups.
     *
     * @throws NotImplementedError if called on a platform which isn't Android or iOS.
     */
    val nonBackedUpStorage: Storage

    /**
     * Gets a [SecureArea] implementation suitable for the platform.
     *
     * @param storage the [Storage] to use for metadata.
     * @throws NotImplementedError if called on a platform which isn't Android or iOS.
     */
    suspend fun getSecureArea(
        storage: Storage = nonBackedUpStorage
    ): SecureArea
}
