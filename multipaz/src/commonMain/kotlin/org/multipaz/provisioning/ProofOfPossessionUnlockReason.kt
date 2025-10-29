package org.multipaz.provisioning

import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea

/**
 * Indicates that a key in [SecureArea] should be unlocked to generate proof-of-possession
 * in a provisioning workflow.
 */
object ProofOfPossessionUnlockReason: UnlockReason