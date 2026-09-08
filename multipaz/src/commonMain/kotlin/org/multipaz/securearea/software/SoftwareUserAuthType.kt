package org.multipaz.securearea.software

/**
 * An enumeration for different user authentication types when using [SoftwareSecureArea].
 */
enum class SoftwareUserAuthType(
    /** The flag bit value for this authentication type. */
    val flagValue: Long
) {
    /**
     * Flag indicating that authentication is needed using the user's knowledge
     * factor for Device Lock, e.g. passcode on iOS or LSKF on Android.
     */
    PASSCODE(1 shl 0),

    /**
     * Flag indicating that authentication is needed using the user's biometric.
     */
    BIOMETRIC(1 shl 1);

    companion object {
        /**
         * Helper to encode a set of [SoftwareUserAuthType] as an integer.
         */
        fun encodeSet(types: Set<SoftwareUserAuthType>): Long {
            var value = 0L
            for (type in types) {
                value = value or type.flagValue
            }
            return value
        }

        /**
         * Helper to decode an integer into a set of [SoftwareUserAuthType].
         *
         * Bits not corresponding to an authentication type are ignored.
         */
        fun decodeSet(types: Long): Set<SoftwareUserAuthType> {
            val result = mutableSetOf<SoftwareUserAuthType>()
            for (type in SoftwareUserAuthType.values()) {
                if ((types and type.flagValue) != 0L) {
                    result.add(type)
                }
            }
            return result
        }
    }
}

/** Decodes the number into a set of [SoftwareUserAuthType] */
val Long.softwareUserAuthTypeSet: Set<SoftwareUserAuthType>
    get() = SoftwareUserAuthType.decodeSet(this)
