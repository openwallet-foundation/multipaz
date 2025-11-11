package org.multipaz.mdoc.zkp.longfellow

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.X509Cert
import org.multipaz.util.Constants
import org.multipaz.util.toHex
import kotlin.time.measureTime

object LongfellowNativeTestsCommon {
    private fun getFormattedCoordinate(value: ByteArray): String {
        return "0x" + value.toHex()
    }

    internal fun runFullVerification(
        proofGenerationAttributes: List<NativeAttribute>,
        proofVerificationAttributes: List<NativeAttribute>
    ): VerifierCodeEnum{
        val zkSpec: LongfellowZkSystemSpec = LongfellowNatives.getLongfellowZkSystemSpec(1)
        val circuit = LongfellowNatives.generateCircuit(zkSpec)

        val sessionTranscript = MdocTestDataProvider.getTranscript()
        val docBytes = MdocTestDataProvider.getMdocBytes()
        val doc = Cbor.decode(docBytes.toByteArray())

        val issuerCert = X509Cert(ByteString(doc["issuerSigned"]["issuerAuth"][1][33].asBstr))
        val ecPubKeyCoordinates = issuerCert.ecPublicKey as EcPublicKeyDoubleCoordinate
        val x = getFormattedCoordinate(ecPubKeyCoordinates.x)
        val y = getFormattedCoordinate(ecPubKeyCoordinates.y)

        val docType = doc["docType"].asTstr
        val formattedDate = MdocTestDataProvider.getProofGenerationDate().format(LocalDateTime.Formats.ISO)

        val longfellowDocBytes = Cbor.encode(
            buildCborMap {
                put("version", "1.0")
                put("documents", CborArray.builder().add(doc).end().build())
                put("status", Constants.DEVICE_RESPONSE_STATUS_OK)
            }
        )

        var proof: Proof
        val proofTimeTaken = measureTime {
            val proofBytes = LongfellowNatives.runMdocProver(
                circuit,
                circuit.size,
                ByteString(longfellowDocBytes),
                longfellowDocBytes.size,
                x,
                y,
                sessionTranscript,
                sessionTranscript.size,
                formattedDate,
                zkSpec,
                proofGenerationAttributes
            )

            proof = Proof(formattedDate, ByteString(proofBytes))
        }

        println("--> Time taken to generate proof: $proofTimeTaken")

        var verifierCodeEnum: VerifierCodeEnum
        val verifyTimeTaken = measureTime {
            val verifierCode = LongfellowNatives.runMdocVerifier(
                circuit,
                circuit.size,
                x,
                y,
                sessionTranscript,
                sessionTranscript.size,
                proof.timestamp,
                proof.proof,
                proof.proof.size,
                docType,
                zkSpec,
                proofVerificationAttributes.toTypedArray()
            )

            verifierCodeEnum = VerifierCodeEnum.fromInt(verifierCode)!!
        }

        println("--> Time taken to verify proof: $verifyTimeTaken")
        return verifierCodeEnum
    }
}
