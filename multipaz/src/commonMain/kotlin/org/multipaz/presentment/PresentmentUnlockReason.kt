package org.multipaz.presentment

import org.multipaz.credential.Credential
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea

/**
 * Value that conveys a request for a key in a [SecureArea] be unlocked in order to present a
 * [Credential] to a Relying Party.
 *
 * @param credential [Credential] that is being presented.
 */
class PresentmentUnlockReason(
    val credential: Credential
): UnlockReason