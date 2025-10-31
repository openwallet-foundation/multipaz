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
        val bytes = this::class.java.getResourceAsStream("/circuits/longfellow-libzk-v1/3_1_bd3168ea0a9096b4f7b9b61d1c210dac1b7126a9ec40b8bc770d4d485efce4e9")
            ?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Resource not found")

        val system = LongfellowZkSystem().apply {
            addCircuit("3_1_bd3168ea0a9096b4f7b9b61d1c210dac1b7126a9ec40b8bc770d4d485efce4e9", ByteString(bytes))
        }

        val testTime = MdocTestDataProvider.getProofGenerationDate().toInstant(TimeZone.UTC)
        val zkRepository = ZkSystemRepository()
        zkRepository.add(system)

        val spec = ZkSystemSpec(
            id = "one_${system.name}",
            system = system.name,
        ).apply {
            addParam(
                "circuitHash",
                "bd3168ea0a9096b4f7b9b61d1c210dac1b7126a9ec40b8bc770d4d485efce4e9"
            )
            addParam("numAttributes", 1)
            addParam("version", 3)
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