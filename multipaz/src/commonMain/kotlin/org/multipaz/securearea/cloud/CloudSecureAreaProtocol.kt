package org.multipaz.securearea.cloud

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cose.CoseKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.Signature
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.DeviceAttestation
import org.multipaz.device.DeviceCheck


/**
 * This describes the protocol (messages and constants) between the device and the server
 * for the Cloud Secure Area.
 *
 * Throughout, the term _device_ or _client_ is used to refer to an Mobile Application (usually a
 * so-called Wallet App) and using a [SecureArea] implementation which is implementing this
 * protocol, for example the [CloudSecureArea] class.
 *
 * Similarly, the term _server_ or _cloud_ is used to refer to server-side of the Cloud Secure Area
 * and  _Secure Area_ is used to refer to the part of this server-side which may be implemented
 * in an isolated execution environment with additional protections.
 *
 * All messages in the protocol are encoded using [CBOR](https://datatracker.ietf.org/doc/html/rfc8949)
 * and is described here according to [CDDL](https://datatracker.ietf.org/doc/rfc8610/).
 * Messages are exchanged using the HTTP protocol (HTTPS), using HTTP POST. It is not
 * required that the client supports HTTP cookies.
 *
 * One trait shared by all messages is that they are all maps and the `"type"` key has a
 * `tstr` value used to identify which message it is, for example `"RegisterRequest0"`
 * or `"E2EEResponse"`.
 *
 * The protocol consists of several flows
 * - Client Registration
 *   - This includes the client and the server exchanging keys (`DeviceBindingKey` and
 *     `CloudBindingKey`) to be used to bootstrap end-to-end encryption between the two parties.
 *     Each key has an attestation to allow both parties to authenticate each other.
 *   - The server authenticates the device using
 *     [Android Keystore Attestation](https://developer.android.com/privacy-and-security/security-key-attestation)
 *     on Android and
 *     [DCAppAttestService](https://developer.apple.com/documentation/devicecheck/dcappattestservice)
 *     on iOS. This enables restricting the service to only authorized
 *     mobile applications that hasn't been tampered with and is running a known good environment.
 *   - The device authenticates the server by inspecting the root certificate of the
 *     attestation and making sure it trusts the public key in the root certificate.
 *   - Detailed description in [RegisterRequest0], [RegisterResponse0], [RegisterRequest1],
 *     and [RegisterResponse1] messages below.
 * - E2EE Setup
 *   - This involves the device and the server exchanging ephemeral keys (`EDeviceKey`
 *     and `ECloudKey`) which are signed by `DeviceBindingKey` and `CloudBindingKey`.
 *     These keys are used to derive symmetric encryption keys (`SKDevice` and `SKCloud`)
 *     used for AES-GCM-128 encryption.
 *   - The derived symmetric encryption keys are intended to only valid for a finite amount
 *     of time, 10 minutes for example. This is enforced by the server asking the device
 *     to go through the E2EE Setup flow again.
 *   - Detailed description for the E2EE setup flow is in [E2EESetupRequest0],
 *     [E2EESetupResponse0], [E2EESetupRequest1], and [E2EESetupResponse1]. See [E2EERequest]
 *     and [E2EEResponse] for how the symmetric encryption keys are used.
 * - Stage 2 registration
 *   - This runs after E2EE has been set up and allows for exchange of registration-related
 *     data which needs to be kept confidential. Currently this only includes the passphrase
 *     used to protect keys.
 *   - Detailed description in [RegisterStage2Request0] and [RegisterStage2Response0].
 *
 * The following flows are all implemented on top of E2EE and requires stage 2 registration
 * to be complete.
 * - Create Key
 *   - For every key created on the server, a companion key on the local device will
 *     be created in Secure Hardware (Android Keystore or iOS Secure Enclave). The local
 *     key is used to enforce User Authentication requirements using the native device
 *     unlock mechanism (e.g. biometric and passcodes).
 *   - See [CreateKeyRequest0], [CreateKeyResponse0], [CreateKeyRequest1], [CreateKeyResponse1]
 *     for a detailed description of this flow.
 * - Sign with Key
 *   - See [SignRequest0], [SignResponse0], [SignRequest1], [SignResponse1] for a detailed
 *     description of this flow.
 * - Key Agreement with Key
 *   - See [KeyAgreementRequest0], [KeyAgreementResponse0], [KeyAgreementRequest1],
 *     [KeyAgreementResponse1] for a detailed description of this flow.
 * - Passphrase check
 *    - The purpose of this is for the device to check ahead of time if the passphrase
 *      the user entered will work. This is useful for UX/UI flows where the client
 *      wishes to collect the passphrase before checking biometrics.
 *    - See [CheckPassphraseRequest] and [CheckPassphraseResponse]
 *
 * The protocol is designed so it's possible to implement a 100% stateless server except
 * that the server needs to possess a symmetric encryption key which never leaves the
 * Secure Area of the server. This key is used to encrypt/decrypt state shipped back
 * and forth to the client.
 *
 * Messages shall be sent from the device to the server as the body of a HTTP POST
 * request and messages from the server to the device shall be in the body of the
 * response. The server shall use HTTP status 200 for responses unless otherwise
 * specified.
 *
 * The following data types are used in the protocol:
 * ```
 * ; A chain of X.509 certificates.
 * ;
 * ; If the chain only contains a single certificate, a bstr with the encoded
 * ; certificate is used. Otherwise an array of encoded certificates is used.
 * ;
 * X509CertChain = bstr / [ bstr]
 *
 * ; A digital signature made with an asymmetric key.
 * ;
 * Signature = EcSignature / RsaSignature / MlDsaSignature
 *
 * EcSignature = {
 *   "r" : bstr,
 *   "s" : bstr
 * }
 *
 * RsaSignature = {
 *   "rsa" : bstr
 * }
 *
 * MlDsaSignature = {
 *   "mldsa" : bstr
 * }
 * ```
 */
object CloudSecureAreaProtocol {

    const val RESULT_OK = 0

    const val RESULT_WRONG_PASSPHRASE = 1

    const val RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS = 2

    @CborSerializable
    sealed class Command {
        companion object
    }

    /**
     * Sent by device a server it has never communicated with before and it wishes to establish communications.
     *
     * @param clientVersion The version of the protocol the client is using. Must be set to "1.0"
     */
    data class RegisterRequest0(
        val clientVersion: String,
    ) : Command()

    /**
     * Message sent by the server to the device sent in response to [RegisterRequest0]:
     *
     * @property attestationChallenge the challenge to use for device attestation.
     * @property cloudChallenge the challenge to use for `DeviceBindingKey`
     * @property serverState an opaque value representing server state.
     */
    data class RegisterResponse0(
        val attestationChallenge: ByteString,
        val cloudChallenge: ByteArray,
        val serverState: ByteArray
    ) : Command()

    /**
     * Message sent by the client to the server in response to [RegisterResponse0].
     *
     * The device uses the [DeviceCheck] API to generate a [DeviceAttestation] and
     * [DeviceAssertion] for the `cloudChallenge` nonce received.
     *
     * The device includes `deviceBindingKeyAttestation` (which is a list of encoded
     * X509 certificates) and `serverState` described in [RegisterRequest0]. The device
     * also generates `deviceChallenge` which is to be included in this message and also
     * stored in the device's session state.
     *
     * Upon receiving this message the server shall check `deviceBindingKeyAttestation`
     * which is an [Android Keystore attestation](https://developer.android.com/training/articles/security-key-attestation)
     * and this include checking that the previously sent `cloudChallenge` is present
     * in the Android Attestation Extension, that verified boot is in state GREEN, that
     * the expected Android application requested the creation of the key, the root
     * public key is well-known (e.g. the Google root), and so on. If a check fails
     * the server shall return HTTP status code 403 (Forbidden).
     *
     * After this, the server shall create `CloudBindingKey` - an EC key using curve
     * P-256 - using `deviceChallenge` as the challenge to be included in the attestation.
     * The attestation format to be used is defined in this protocol, see TODO
     * for the encoding of the attestation extension and the OID to include it at.
     *
     * The server proceeds to prepare a [RegisterResponse1] message.
     */
    data class RegisterRequest1(
        val deviceChallenge: ByteArray,
        val deviceAttestation: DeviceAttestation,
        val deviceBindingKey: CoseKey,
        val deviceBindingKeyAttestation: X509CertChain?,
        val serverState: ByteArray
    ) : Command()

    /**
     * Message sent by the server to the device in response to [RegisterRequest1]:
     *
     * The server includes `cloudBindingKeyAttestation` (which is a list of encoded
     * X509 certificates) and `serverState` described in [RegisterRequest1].
     *
     * When the device receives this message it checks that `cloudBindingKeyAttestation`
     * includes the previously sent `deviceChallenge` in its attestation, that each certificate
     * is signed by the next one, the root public key is well-known (e.g. a the root of the
     * Cloud Secure Area provider), and so on. If a check fails the device shall discard
     * any received state and report an error to the application.
     *
     * If successful, this concludes the first stage of the registration process and the
     * device stores `serverState` locally as "registration context". This is sufficient
     * to set up an E2EE connection (see [E2EESetupRequest0]) which can be used to complete
     * the second and final stage of registration (see [RegisterStage2Request0]).
     */
    data class RegisterResponse1(
        val cloudBindingKeyAttestation: X509CertChain,
        val serverState: ByteArray
    ) : Command()

    /**
     * This message is sent when the device wishes to set up end-to-end
     * encryption to the Secure Area of the server.
     *
     * After receiving this message, the server responds with a [E2EESetupResponse0] message.
     *
     * @property registrationContext is the value obtained from the registration flow, see [RegisterResponse1].
     *
     */
    data class E2EESetupRequest0(
        val registrationContext: ByteArray
    ) : Command()

    /**
     * This is sent in response to [E2EESetupRequest0].
     *
     * When the devices receives this message it shall generate `deviceNonce`
     * and create `EDeviceKey` which shall be a EC key using curve P-256. It
     * is not a requirement to use hardware-backed keystore for `EDeviceKey`.
     *
     * The device then uses `DeviceBindingKey` (created at registration time) to
     * create an ECDSA signature over the bytes of the CBOR
     * ```
     * DataSignedByDevice = [
     *   COSE_Key,               ; EDeviceKey.pub
     *   bstr,                   ; cloudNonce
     *   bstr                    ; deviceNonce
     * ]
     * ```
     * The device proceeds to prepare a [E2EESetupRequest1] message.
     *
     * @property cloudNonce
     * @property serverState
     */
    data class E2EESetupResponse0(
        val cloudNonce: ByteArray,
        val serverState: ByteArray
    ) : Command()

    /**
     * This is sent by the device in response to the [E2EESetupResponse0]:
     * ```
     * E2EESetupRequest1 = {
     *   "E2EESetupRequest1",
     *   "eDeviceKey" : COSE_Key,
     *   "deviceNonce" : bstr
     *   "signature" : EcSignature
     *   "serverState" : bstr
     * }
     * ```
     * where `eDeviceKey`, `deviceNonce`, `signature`, and `serverState` are all
     * described in the [E2EESetupResponse0] message.
     *
     * Upon receiving this message the server builds up `DataSignedByDevice` and checks
     * the signature was made by `DeviceBindingKey` (as received during registration phase).
     * If this fails, the server shall return HTTP status code 403 (Forbidden).
     *
     * If the check is successful, the server creates `ECloudKey` which shall be a EC key
     * using curve P-256. The server then uses `CloudBindingKey` (created at registration time)
     * to create an ECDSA signature over the bytes of the CBOR
     * ```
     * DataSignedByServer = [
     *   COSE_Key,               ; ECloudKey.pub
     *   bstr,                   ; cloudNonce
     *   bstr                    ; deviceNonce
     * ]
     * ```
     * The server proceeds to prepare a [E2EESetupResponse1] message.
     */
    data class E2EESetupRequest1(
        val eDeviceKey: CoseKey,
        val deviceNonce: ByteArray,
        val signature: EcSignature,
        val deviceAssertion: DeviceAssertion,
        val serverState: ByteArray
    ) : Command()

    /**
     * This is sent in response to [E2EESetupRequest1]:
     * ```
     * E2EESetupResponse1 = {
     *   "type" : "E2EESetupResponse1",
     *   "eCloudKey" : COSE_Key,
     *   "signature" : EcSignature,
     *   "serverState" : bstr
     * }
     * ```
     * where `eCloudKey`, `signature`, and `serverState` are all described in
     * the [E2EESetupRequest1] message.
     *
     * Upon receiving this message the device builds up `DataSignedByServer` and checks
     * the signature was made by `CloudBindingKey` (as received during registration phase).
     * If the check fails the device shall discard any received state and report an error
     * to the application.
     *
     * On success, both the server and the device are now able to calculate session
     * encryption keys. Let ZAB be the output of ECKA-DH (Elliptic Curve Key Agreement
     * Algorithm – Diffie-Hellman) as defined in BSI TR-03111 where the inputs shall be
     * `EDeviceKey` and `ECloudKey.pub` on the device side and `ECloudKey` and `EDeviceKey.pub`
     * on the server side.
     *
     * `SKDevice` shall be derived using HKDF as defined in RFC 5869 with the following parameters:
     * - Hash: SHA-256
     * - IKM: ZAB
     * - salt: SHA-256(E2EESetupTranscript)
     * - info: "SKDevice" (encoded as a UTF-8 string without the quotes)
     * - Length: 32 octets
     *
     * `SKCloud` shall be derived using HKDF as defined in RFC 5869 with the following parameters:
     * - Hash: SHA-256
     * - IKM: ZAB
     * - salt: SHA-256(E2EESetupTranscript)
     * - info: "SKCloud" (encoded as a UTF-8 string without the quotes)
     * - Length: 32 octets
     *
     * where `E2EESetupTranscript` is defined as bytes of CBOR conforming to the following CDDL
     * ```
     * E2EESetupTranscript = [
     *   cloudNonce,
     *   deviceNonce
     * ]
     * ```
     */
    data class E2EESetupResponse1(
        val eCloudKey: CoseKey,
        val signature: EcSignature,
        val serverState: ByteArray
    ) : Command()

    data class E2EERequest(
        val encryptedRequest: ByteArray,
        val e2eeContext: ByteArray
    ) : Command()

    data class E2EEResponse(
        val encryptedResponse: ByteArray,
        val e2eeContext: ByteArray
    ) : Command()

    data class RegisterStage2Request0(
        val passphrase: String
    ) : Command()

    data class RegisterStage2Response0(
        val serverState: ByteArray
    ) : Command()

    data class CreateKeyRequest0(
        val algorithm: String,
        val validFromMillis: Long,
        val validUntilMillis: Long,
        val passphraseRequired: Boolean,
        val userAuthenticationRequired: Boolean,
        val userAuthenticationTypes: Long,
        val challenge: ByteArray
    ) : Command()

    data class CreateKeyResponse0(
        val cloudChallenge: ByteArray,
        val serverState: ByteArray
    ) : Command()

    data class CreateKeyRequest1(
        val localKey: CoseKey,
        val localKeyAttestation: X509CertChain?,
        val serverState: ByteArray
    ) : Command()

    data class CreateKeyResponse1(
        val remoteKeyAttestation: X509CertChain,
        val serverState: ByteArray
    ) : Command()

    data class BatchCreateKeyRequest0(
        val algorithm: String,
        val validFromMillis: Long,
        val validUntilMillis: Long,
        val passphraseRequired: Boolean,
        val userAuthenticationRequired: Boolean,
        val userAuthenticationTypes: Long,
        val challenge: ByteArray
    ) : Command()

    data class BatchCreateKeyResponse0(
        val cloudChallenge: ByteArray,
        val serverState: ByteArray
    ) : Command()

    /**
     * The second request for batch creation of keys.
     *
     * Note that the size the number of keys requested in the batch is conveyed here as the size of [localKeys].
     *
     * @property localKeys the local keys created with the challenge retrieved from the server.
     * @property localKeyAttestations the attestations for the local keys created - is empty if the device doesn't
     *   support key attestations. Otherwise its cardinality matches that of [localKeys]
     */
    data class BatchCreateKeyRequest1(
        val localKeys: List<CoseKey>,
        val localKeyAttestations: List<X509CertChain>,
        val serverState: ByteArray
    ) : Command()

    /**
     * The second and final response for batch creation of keys.
     *
     * @property commonCertChain the X.509 certificate that all the leaf certificates got in common.
     * @property remoteKeyAttestationLeafs Leaf X.509 certificates for each generated cloud key.
     * @property serverStates A serverState for each generated cloud key.
     * @property openid4vciKeyAttestationCompactSerialization An attestation over all the keys according to
     *   [OpenID4VCI Key Attestation](https://openid.github.io/OpenID4VCI/openid-4-verifiable-credential-issuance-wg-draft.html#name-key-attestation-in-jwt-form)
     *   or `null` if the implementation doesn't provide such an attestation or wasn't requested.
     */
    data class BatchCreateKeyResponse1(
        val commonCertChain: X509CertChain,
        val remoteKeyAttestationLeafs: List<X509Cert>,
        val serverStates: List<ByteArray>,
        val openid4vciKeyAttestationCompactSerialization: String?
    ) : Command()

    data class SignRequest0(
        val dataToSign: ByteArray,
        val keyContext: ByteArray
    ) : Command()

    data class SignResponse0(
        val cloudNonce: ByteArray,
        val serverState: ByteArray
    ) : Command()

    data class SignRequest1(
        val signature: EcSignature,
        val passphrase: String?,
        val serverState: ByteArray
    ) : Command()

    /**
     * The second and final response for signing data.
     *
     * @property result the result code.
     * @property signature the signature, or `null` if signing failed.
     * @property waitDurationMillis duration to wait before retrying in milliseconds.
     */
    data class SignResponse1(
        val result: Int,
        val signature: Signature?,
        val waitDurationMillis: Long
    ) : Command()

    data class KeyAgreementRequest0(
        val otherPublicKey: CoseKey,
        val keyContext: ByteArray
    ) : Command()

    data class KeyAgreementResponse0(
        val cloudNonce: ByteArray,
        val serverState: ByteArray
    ) : Command()

    data class KeyAgreementRequest1(
        val signature: EcSignature,
        val passphrase: String?,
        val serverState: ByteArray
    ) : Command()

    data class KeyAgreementResponse1(
        val result: Int,
        val zab: ByteArray?,
        val waitDurationMillis: Long
    ) : Command()

    /**
     * The first request to decapsulate a shared secret using a KEM key.
     *
     * @property ciphertext the encapsulated ciphertext.
     * @property keyContext the encrypted key context for the key.
     */
    data class KemDecapsulateRequest0(
        val ciphertext: ByteArray,
        val keyContext: ByteArray
    ) : Command()

    /**
     * The first response to a KEM decapsulate request.
     *
     * @property cloudNonce a nonce generated by the cloud server.
     * @property serverState state returned by the server to be included in the next request.
     */
    data class KemDecapsulateResponse0(
        val cloudNonce: ByteArray,
        val serverState: ByteArray
    ) : Command()

    /**
     * The second request to decapsulate a shared secret using a KEM key.
     *
     * @property signature signature over the cloud nonce using the local key.
     * @property passphrase the passphrase, if required and provided, or `null`.
     * @property serverState state returned by the server in [KemDecapsulateResponse0].
     */
    data class KemDecapsulateRequest1(
        val signature: EcSignature,
        val passphrase: String?,
        val serverState: ByteArray
    ) : Command()

    /**
     * The second and final response to a KEM decapsulate request.
     *
     * @property result the result code, such as [RESULT_OK] or [RESULT_WRONG_PASSPHRASE].
     * @property sharedSecret the decapsulated shared secret, or `null` if failed.
     * @property waitDurationMillis duration to wait before retrying in milliseconds.
     */
    data class KemDecapsulateResponse1(
        val result: Int,
        val sharedSecret: ByteArray?,
        val waitDurationMillis: Long
    ) : Command()

    /**
     * Checks if the given passphrase matches the registered passphrase.
     *
     * This can be used in passphrase dialogs to check the passphrase
     * obtained from the user will work for operations like signing or
     * key agreement without having to try the whole operation again.
     *
     * Wrong passphrase attempts will continue to contribute to policy
     * enforced by [PassphraseFailureEnforcer].
     */
    data class CheckPassphraseRequest(
        val passphrase: String
    ) : Command()

    /**
     * Response for [CheckPassphraseRequest].
     *
     * The result is one of [RESULT_OK], [RESULT_WRONG_PASSPHRASE], and
     * [RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS].
     */
    data class CheckPassphraseResponse(
        val result: Int,
    ) : Command()
}