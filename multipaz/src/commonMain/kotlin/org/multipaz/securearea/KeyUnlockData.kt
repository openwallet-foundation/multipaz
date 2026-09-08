package org.multipaz.securearea

/**
 * Abstract type with information used when operating on a key that
 * has been unlocked.
 */
interface KeyUnlockData {
    /**
     * The [SecureArea] containing the key for which this unlock data applies.
     */
    val secureArea: SecureArea

    /**
     * The alias of the key for which this unlock data applies.
     */
    val alias: String
}