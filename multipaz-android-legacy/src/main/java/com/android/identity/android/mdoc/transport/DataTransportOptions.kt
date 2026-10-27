package com.android.identity.android.mdoc.transport

/**
 * A set of options used when creating a [DataTransport] derived instance.
 */
class DataTransportOptions internal constructor(
    /**
     * The preference for using BLE L2CAP transmission profile.
     *
     * If true, L2CAP will be used if supported by the OS and remote mdoc.
     */
    val bleUseL2CAP: Boolean,

    /**
     * The preference to clear the BLE Service Cache before service discovery when
     * acting as a GATT Client.
     */
    val bleClearCache: Boolean,
) {
    /**
     * A builder for [DataTransportOptions].
     */
    class Builder {
        private var bleUseL2CAP = false
        private var bleClearCache = false

        /**
         * Sets the preference for use BLE L2CAP transmission profile.
         *
         * Use L2CAP if supported by the OS and remote mdoc.
         *
         * The default value for this is `false`.
         *
         * @param value indicates if it should use L2CAP socket if available.
         * @return the builder.
         */
        fun setBleUseL2CAP(value: Boolean) = apply {
            bleUseL2CAP = value
        }

        /**
         * Sets whether to clear the BLE Service Cache before service discovery when acting as
         * a GATT Client.
         *
         * The default value for this is `false`.
         *
         * @param value indicates if the BLE Service Cache should be cleared.
         * @return the builder.
         */
        fun setBleClearCache(value: Boolean) = apply {
            bleClearCache = value
        }

        /**
         * Builds the [DataTransportOptions].
         *
         * @return the built [DataTransportOptions] instance.
         */
        fun build(): DataTransportOptions {
            return DataTransportOptions(
                bleUseL2CAP,
                bleClearCache,
            )
        }
    }
}
