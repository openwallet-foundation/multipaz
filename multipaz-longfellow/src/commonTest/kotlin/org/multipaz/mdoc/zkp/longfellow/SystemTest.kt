package org.multipaz.mdoc.zkp.longfellow

import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.zkp.ProofVerificationFailureException
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.verification.VerificationUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

class SystemTest {
    @Test
    fun testProofFullFlow_success() = runTest {

        val system = LongfellowZkSystem()
        system.addDefaultCircuits()

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
            .verifyProof(zkDocResponse, spec, sessionTranscript, atTime = testTime)

        assertFailsWith<ProofVerificationFailureException> {
            zkRepository.lookup(system.name)!!
                .verifyProof(zkDocResponse, spec, sessionTranscript, atTime = testTime + 10.minutes)
        }
        assertFailsWith<ProofVerificationFailureException> {
            zkRepository.lookup(system.name)!!
                .verifyProof(zkDocResponse, spec, sessionTranscript, atTime = testTime - 10.minutes)
        }

        val tamperedWithDeviceSigned = zkDocResponse.copy(
            documentData = zkDocResponse.documentData.copy(
                deviceSigned = mapOf("org.iso.23220.5.1" to mapOf("CHV_1" to Simple.TRUE))
            )
        )
        assertFailsWith<ProofVerificationFailureException> {
            zkRepository.lookup(system.name)!!
                .verifyProof(tamperedWithDeviceSigned, spec, sessionTranscript, atTime = testTime)
        }
    }

    @Test
    fun testCustomReaderZkSystemIdAndMultiCertMsoX5chain() = runTest {
        val system = LongfellowZkSystem()
        system.addDefaultCircuits()

        val testTime = MdocTestDataProvider.getProofGenerationDate().toInstant(TimeZone.UTC)
        val zkRepository = ZkSystemRepository().add(system)

        val customReaderSpec = ZkSystemSpec(
            id = "reader-custom-zk-id-42",
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

        val requestedClaim = MdocRequestedClaim(
            docType = "org.iso.18013.5.1.mDL",
            namespaceName = "org.iso.18013.5.1",
            dataElementName = "age_over_18",
            intentToRetain = false
        )
        val matchedSpec = assertNotNull(
            system.getMatchingSystemSpec(
                zkSystemSpecs = listOf(customReaderSpec),
                requestedClaims = listOf(requestedClaim)
            )
        )
        assertEquals("reader-custom-zk-id-42", matchedSpec.id)

        val sessionTranscript = Cbor.decode(MdocTestDataProvider.getTranscript().toByteArray())
        val document = MdocDocument.fromDataItem(Cbor.decode(MdocTestDataProvider.getMdocBytes().toByteArray()))

        val zkDoc = system.generateProof(
            zkSystemSpec = matchedSpec,
            document = document,
            sessionTranscript = sessionTranscript,
            timestamp = testTime
        ).copy(docRequestIds = listOf(0))

        assertEquals("reader-custom-zk-id-42", zkDoc.documentData.zkSystemSpecId)
        assertEquals(document.issuerCertChain, zkDoc.documentData.msoX5chain)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = emptyMap()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf("org.iso.18013.5.1" to mapOf("age_over_18" to false)),
                docRequestInfo = DocRequestInfo(
                    zkRequest = ZkRequest(
                        systemSpecs = listOf(customReaderSpec),
                        zkRequired = true
                    )
                )
            )
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(zkDoc)
        }
        val presentations = VerificationUtil.verifyMdocDeviceResponse(
            now = testTime,
            deviceResponse = deviceResponse.toDataItem(),
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = null,
            zkSystemRepository = zkRepository,
            request = deviceRequest,
        )
        assertEquals(1, presentations.size)
    }
}