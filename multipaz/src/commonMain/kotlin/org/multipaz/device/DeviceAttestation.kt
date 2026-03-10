package org.multipaz.device

import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A platform-issued statement vouching for the integrity of the wallet app.
 *
 * Validity checks are cross-platform, as we need to be able to run them on the server
 * (e.g. one does not have to be on iOS to validate [DeviceAttestationIos]).
 */
@CborSerializable
sealed class DeviceAttestation {
    /**
     * Check the validity of this [DeviceAttestation].
     *
     * If validity cannot be confirmed, [DeviceAttestationException] is thrown.
     *
     * @param validationData validation criteria
     * @param validateAt time instant at which validity should be checked
     */
    abstract suspend fun validate(
        validationData: DeviceAttestationValidationData,
        validateAt: Instant = Clock.System.now()
    )

    /**
     * Check the validity of [assertion] in the context of this [DeviceAttestation].
     *
     * If validity cannot be confirmed, [DeviceAssertionException] is thrown.
     */
    abstract suspend fun validateAssertion(assertion: DeviceAssertion)

    companion object
}