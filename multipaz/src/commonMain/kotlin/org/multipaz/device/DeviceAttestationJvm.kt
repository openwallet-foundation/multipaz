package org.multipaz.device

/** Plain JVM does not have a way to generate a device attestation. */
class DeviceAttestationJvm() : DeviceAttestation() {
    override suspend fun validate(validationData: DeviceAttestationValidationData) {
        throw DeviceAttestationException("DeviceAttestationJvm is not trusted")
    }

    override suspend fun validateAssertion(assertion: DeviceAssertion) {
        throw DeviceAssertionException("DeviceAttestationJvm is not trusted")
    }
}