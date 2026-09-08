package org.multipaz.securearea.software

import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea

/**
 * A class that can be used to provide information used for unlocking a key.
 *
 * @param secureArea the [SoftwareSecureArea].
 * @param alias the alias of the key.
 * @param passphrase the passphrase or `null` if no passphrase is needed/provided.
 * @param userAuthenticated whether the user was authenticated.
 */
class SoftwareKeyUnlockData(
    override val secureArea: SoftwareSecureArea,
    override val alias: String,
    val passphrase: String? = null,
    val userAuthenticated: Boolean = false
) : KeyUnlockData