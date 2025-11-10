package org.multipaz.mdoc.zkp.longfellow

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.io.bytestring.ByteString
import org.multipaz.mdoc.response.DeviceResponseGenerator
import org.multipaz.mdoc.response.DeviceResponseParser

class SystemTest {
    @Test
    fun testProofFullFlow_success() {
        val bytes = this::class.java.getResourceAsStream("/circuits/longfellow-libzk-v1/6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6")
            ?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Resource not found")

        val system = LongfellowZkSystem().apply {
            addCircuit("6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6", ByteString(bytes))
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

        val sessionTranscript = MdocTestDataProvider.getTranscript()
        val docBytes = MdocTestDataProvider.getMdocBytes().toByteArray()

        val zkDoc = zkRepository.lookup(system.name)?.generateProof(spec, ByteString(docBytes), sessionTranscript, testTime)

        val responseGenerator = DeviceResponseGenerator(0)
        responseGenerator.addZkDocument(zkDoc!!)
        val responseBytes = responseGenerator.generate()
        val responseParser = DeviceResponseParser(responseBytes, sessionTranscript.toByteArray())
        val response = runBlocking {
            responseParser.parse()
        }

        val zkDocResponse = response.zkDocuments[0]

        zkRepository.lookup(system.name)?.verifyProof(zkDocResponse, spec, sessionTranscript)
    }
}