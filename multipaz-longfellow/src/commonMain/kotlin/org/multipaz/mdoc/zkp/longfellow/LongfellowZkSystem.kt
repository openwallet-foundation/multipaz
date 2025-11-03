package org.multipaz.mdoc.zkp.longfellow

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.mdoc.zkp.ProofVerificationFailureException
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.request.MdocRequest
import kotlin.time.Instant
import org.multipaz.cbor.putCborArray
import org.multipaz.request.RequestedClaim
import org.multipaz.util.toHex
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

private data class CircuitEntry (
    val zkSystemSpec: ZkSystemSpec,
    val longfellowZkSystemSpec: LongfellowZkSystemSpec,
    val circuitBytes: ByteString,
)

/**
 * Abstract base class for Longfellow-based ZK systems implementing [ZkSystem].
 *
 * Provides core logic for proof generation and verification using native Longfellow
 * libraries. Circuit files are expected to be name with the format:
 * `<version>_<numAttributes>_<circuitHash>`.
 */
class LongfellowZkSystem(): ZkSystem {
    private val circuits: MutableList<CircuitEntry> = mutableListOf()

    override val systemSpecs: List<ZkSystemSpec>
        get() = this.circuits.map { it.zkSystemSpec }

    companion object {
        private const val TAG = "LongfellowZkSystem"
    }

    override val name: String
        get() = "longfellow-libzk-v1"

    private fun getFormattedCoordinate(value: ByteArray): String {
        return "0x" + value.toHex()
    }

    private fun formatDate(timestamp: Instant): String {
        return timestamp.toLocalDateTime(TimeZone.UTC).format(LocalDateTime.Formats.ISO)
    }

    private fun getLongfellowZkSystemSpec(zkSystemSpec: ZkSystemSpec): LongfellowZkSystemSpec? {
        val entry = circuits.find { circuitEntry ->
            val circuitSpec = circuitEntry.zkSystemSpec

            circuitSpec.getParam<String>("circuitHash") == zkSystemSpec.getParam<String>("circuitHash") &&
                    circuitSpec.getParam<Long>("version") == zkSystemSpec.getParam<Long>("version") &&
                    circuitSpec.getParam<Long>("numAttributes") == zkSystemSpec.getParam<Long>("numAttributes")
        }

        return entry?.longfellowZkSystemSpec
    }

    private fun getCircuitBytes(zkSystemSpec: ZkSystemSpec): ByteString? {
        val entry = circuits.find { circuitEntry ->
            val circuitSpec = circuitEntry.zkSystemSpec

            circuitSpec.getParam<String>("circuitHash") == zkSystemSpec.getParam<String>("circuitHash") &&
            circuitSpec.getParam<Long>("version") == zkSystemSpec.getParam<Long>("version") &&
            circuitSpec.getParam<Long>("numAttributes") == zkSystemSpec.getParam<Long>("numAttributes")
        }

        return entry?.circuitBytes
    }

    private fun parseCircuitFilename(circuitFileName: String): Pair<ZkSystemSpec, LongfellowZkSystemSpec>? {
        val circuitNameParts = circuitFileName.split("_")
        if (circuitNameParts.size != 5) {
            Logger.w(TAG, "$circuitFileName does not match expected " +
                "<version>_<numAttributes>_<blockEncHash>_<blockEncSig>_<hash>")
            return null
        }

        val version = circuitNameParts[0].toLongOrNull()
        if (version == null) {
            Logger.w(TAG, "$circuitFileName does not match expected format, could not find version number.")
            return null
        }

        val numAttributes = circuitNameParts[1].toLongOrNull()
        if (numAttributes == null) {
            Logger.w(TAG, "$circuitFileName does not match expected format, could not find number of attributes.")
            return null
        }

        val blockEncHash = circuitNameParts[2].toLongOrNull()
        if (blockEncHash == null) {
            Logger.w(TAG, "$circuitFileName does not match expected format, could not find blockEncHash.")
            return null
        }

        val blockEncSig = circuitNameParts[3].toLongOrNull()
        if (blockEncSig == null) {
            Logger.w(TAG, "$circuitFileName does not match expected format, could not find blockEncSig.")
            return null
        }

        val circuitHash = circuitNameParts[4]

        val spec = ZkSystemSpec(
            id = "${name}_${circuitFileName}",
            system = name,
        )
        spec.addParam("version", version)
        spec.addParam("circuitHash", circuitHash)
        spec.addParam("numAttributes", numAttributes)

        val longfellowSpec = LongfellowZkSystemSpec(
            system = name,
            circuitHash = circuitHash,
            numAttributes = numAttributes,
            version = version,
            blockEncHash = blockEncHash,
            blockEncSig = blockEncSig
        )

        return Pair(spec, longfellowSpec)
    }

    override fun generateProof(
        zkSystemSpec: ZkSystemSpec,
        encodedDocument: ByteString,
        encodedSessionTranscript: ByteString,
        timestamp: Instant
    ): ZkDocument {
        val longfellowZkSystemSpec = getLongfellowZkSystemSpec(zkSystemSpec)
            ?: throw IllegalArgumentException("Circuit not found for system spec: $zkSystemSpec")
        val circuitBytes = getCircuitBytes(zkSystemSpec)
            ?: throw IllegalArgumentException("Circuit not found for system spec: $zkSystemSpec")

        val doc = Cbor.decode(encodedDocument.toByteArray())
        // The Longfellow ZKP library expects this format, and will grab the 1st document
        // in the array.
        val longfellowDocBytes = Cbor.encode(
            buildCborMap {
                put("version", "1.0")
                putCborArray("documents") {
                    add(doc)
                }
                put("status", Constants.DEVICE_RESPONSE_STATUS_OK)
            }
        )

        val docType = doc["docType"].asTstr

        val issuerCert = X509Cert(doc["issuerSigned"]["issuerAuth"][1][33].asBstr)
        val ecPubKeyCoordinates = issuerCert.ecPublicKey as EcPublicKeyDoubleCoordinate
        val x = getFormattedCoordinate(ecPubKeyCoordinates.x)
        val y = getFormattedCoordinate(ecPubKeyCoordinates.y)

        val attributes = mutableListOf<NativeAttribute>()
        val issuerSigned = mutableMapOf<String, Map<String, DataItem>>()
        for ((nameSpaceItem, dataElementsItem) in doc["issuerSigned"]["nameSpaces"].asMap) {
            val namespace = nameSpaceItem.asTstr
            val values = mutableMapOf<String, DataItem>()
            for (encodedIssuerSignedItem in dataElementsItem.asArray) {
                val issuerSignedItem = encodedIssuerSignedItem.asTaggedEncodedCbor
                val dataElementName = issuerSignedItem["elementIdentifier"].asTstr
                val dataElementValue = issuerSignedItem["elementValue"]
                values.put(dataElementName, dataElementValue)
                attributes.add(NativeAttribute(
                    key = dataElementName,
                    namespace = namespace,
                    value = Cbor.encode(dataElementValue)
                ))
            }
            issuerSigned.put(nameSpaceItem.asTstr, values)
        }

        val proof = LongfellowNatives.runMdocProver(
            circuitBytes,
            circuitBytes.size,
            ByteString(longfellowDocBytes),
            longfellowDocBytes.size,
            x,
            y,
            encodedSessionTranscript,
            encodedSessionTranscript.size,
            formatDate(timestamp),
            longfellowZkSystemSpec,
            attributes
        )

        val zkDocument = ZkDocument(
            proof=ByteString(proof),
            zkDocumentData = ZkDocumentData (
                zkSystemSpecId = zkSystemSpec.id,
                docType = docType,
                timestamp = timestamp,
                issuerSigned = issuerSigned,
                deviceSigned = emptyMap(),     // TODO: support deviceSigned in Longfellow
                msoX5chain = X509CertChain(listOf(issuerCert)),
            )
        )

        return zkDocument
    }

    override fun verifyProof(zkDocument: ZkDocument, zkSystemSpec: ZkSystemSpec, encodedSessionTranscript: ByteString) {
        if (zkDocument.zkDocumentData.msoX5chain == null || zkDocument.zkDocumentData.msoX5chain!!.certificates.isEmpty()) {
            throw IllegalArgumentException("zkDocument must contain at least 1 certificate in msoX5chain.")
        }

        val cert = zkDocument.zkDocumentData.msoX5chain!!.certificates[0]
        val ecPubKeyCoordinates = cert.ecPublicKey as EcPublicKeyDoubleCoordinate
        val x = getFormattedCoordinate(ecPubKeyCoordinates.x)
        val y = getFormattedCoordinate(ecPubKeyCoordinates.y)

        val longfellowZkSystemSpec = getLongfellowZkSystemSpec(zkSystemSpec)
            ?: throw IllegalArgumentException("Circuit not found for system spec: $zkSystemSpec")
        val circuitBytes = getCircuitBytes(zkSystemSpec)
            ?: throw IllegalArgumentException("Circuit not found for system spec: $zkSystemSpec")

        val attributes = mutableListOf<NativeAttribute>()
        for ((nameSpaceName, dataElements) in zkDocument.zkDocumentData.issuerSigned) {
            for ((dataElementName, dataElementValue) in dataElements) {
                attributes.add(NativeAttribute(
                    key = dataElementName,
                    namespace = nameSpaceName,
                    value = Cbor.encode(dataElementValue)
                ))
            }
        }

        val verifierResult = LongfellowNatives.runMdocVerifier(
            circuitBytes,
            circuitBytes.size,
            x,
            y,
            encodedSessionTranscript,
            encodedSessionTranscript.size,
            formatDate(zkDocument.zkDocumentData.timestamp),
            zkDocument.proof,
            zkDocument.proof.size,
            zkDocument.zkDocumentData.docType,
            longfellowZkSystemSpec,
            attributes.toTypedArray()
        )

        Logger.i(TAG, "Verification Code: $verifierResult")

        if (verifierResult != VerifierCodeEnum.MDOC_VERIFIER_SUCCESS.value) {
            val verifierCodeEnum = VerifierCodeEnum.fromInt(verifierResult)
            throw ProofVerificationFailureException("Verification failed with error: $verifierCodeEnum")
        }
    }

    /**
     * Longfellow encodes a version number, the number of attributes, and the circuit
     * hash in the filename with the circuit data so in addition to `circuitBytes`, pass this
     * information in `circuitFilename` encoded in the following way:
     * `<version>_<numAttributes>_<circuitHash>`.
     * circuitFilename should be only the name of the file, and must not include any path separators.
     *
     * @param circuitFilename the name of the circuit file
     * @param circuitBytes the bytes of the circuit file
     * @throws IllegalArgumentException if the circuitFilename is invalid
     */
    fun addCircuit(circuitFilename: String, circuitBytes: ByteString): Boolean {
        require(circuitFilename.indexOf("/") == -1) {
            "circuitFilename must not include any directory separator"
        }

        val spec = parseCircuitFilename(circuitFilename)
        if (spec == null) {
            Logger.w(TAG, "Invalid circuit file name: $circuitFilename")
            return false
        }

        return circuits.add(CircuitEntry(spec.first, spec.second, circuitBytes))
    }

    /**
     * Finds the best matching [ZkSystemSpec] from a given list based on the number of signed attributes.
     *
     * @param zkSystemSpecs the available specs from the request
     * @param mdocRequest the request to fulfill
     * @return the best matching [ZkSystemSpec], or null if none are suitable
     */
    override fun getMatchingSystemSpec(
        zkSystemSpecs: List<ZkSystemSpec>,
        requestedClaims: List<RequestedClaim>
    ): ZkSystemSpec? {
        val numAttributesRequested = requestedClaims.size.toLong()
        if (numAttributesRequested == 0L) {
            return null
        }

        // Get the set of allowed circuit hashes from the input list for efficient lookup.
        val allowedCircuitHashes = zkSystemSpecs
            .mapNotNull { it.getParam<String>("circuitHash") }
            .toSet()

        // If no valid hashes are provided from the input list, we cannot find a match.
        if (allowedCircuitHashes.isEmpty()) {
            return null
        }

        return this.systemSpecs
            .filter { spec ->
                val circuitHash = spec.getParam<String>("circuitHash")
                val hashMatches = (circuitHash != null && circuitHash in allowedCircuitHashes)
                val numAttributesMatch = spec.getParam<Long>("numAttributes") == numAttributesRequested
                hashMatches && numAttributesMatch
            }
            .sortedBy { it.getParam<Long>("version") ?: Long.MIN_VALUE }
            .firstOrNull()
    }
}
