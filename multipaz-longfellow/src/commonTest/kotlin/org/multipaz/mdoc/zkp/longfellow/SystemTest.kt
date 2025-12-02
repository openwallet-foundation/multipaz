package org.multipaz.mdoc.zkp.longfellow

import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import kotlin.test.Test

expect fun loadCircuit(): Pair<String, ByteString>?

class SystemTest {
    @Test
    fun testProofFullFlow_success() = runTest {

        val circuit = loadCircuit()
        if (circuit == null) {
            println("Skipping SystemTest on this platform because no circuit is defined")
            return@runTest
        }
        val system = LongfellowZkSystem().apply {
            addCircuit(
                circuitFilename = circuit.first,
                circuitBytes = circuit.second
            )
        }

        val testTime = MdocTestDataProvider.getProofGenerationDate().toInstant(TimeZone.UTC)
        val zkRepository = ZkSystemRepository()
        zkRepository.add(system)

        val spec = ZkSystemSpec(
            id = "one_${system.name}",
            system = system.name,
        ).apply {
            addParam(
                "circuit_hash",
                "137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6"
            )
            addParam("num_attributes", 1)
            addParam("version", 6)
            addParam("block_enc_hash", 4096)
            addParam("block_enc_sig", 2945)
        }

        val sessionTranscript = Cbor.decode(MdocTestDataProvider.getTranscript().toByteArray())
        val document = MdocDocument.fromDataItem(Cbor.decode(MdocTestDataProvider.getMdocBytes().toByteArray()))

        val zkDoc = zkRepository.lookup(system.name)!!
            .generateProof(
                zkSystemSpec = spec,
                document = document,
                sessionTranscript = sessionTranscript,
                timestamp = testTime
            )

        val encodedDeviceResponse = Cbor.encode(
            buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
            ) {
                addZkDocument(zkDoc)
            }.toDataItem()
        )
        val parsedResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        val zkDocResponse = parsedResponse.zkDocuments.first()

        zkRepository.lookup(system.name)!!
            .verifyProof(zkDocResponse, spec, sessionTranscript)
    }
}