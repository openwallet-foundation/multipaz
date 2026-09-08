package org.multipaz.securearea.cloud

import org.multipaz.securearea.KeyUnlockData

/**
 * A class to provide information used for unlocking a Cloud Secure Area key.
 *
 * @param secureArea the [CloudSecureArea] containing the key.
 * @param alias the alias of the key to unlock.
 */
class CloudKeyUnlockData(
    override val secureArea: CloudSecureArea,
    override val alias: String
) : KeyUnlockData {

    /**
     * The passphrase used to unlock the key or `null` if a passphrase isn't required.
     */
    var passphrase: String? = null
}