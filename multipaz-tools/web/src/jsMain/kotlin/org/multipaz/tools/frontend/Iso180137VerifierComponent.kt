@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cose.CoseKey
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.utopia.knowntypes.addUtopiaTypes
import org.multipaz.mdoc.request.AlternativeDataElementSet
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.ElementReference
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.mdoc.zkp.longfellow.LongfellowZkSystem
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import org.multipaz.verification.MdocApiDcResponse
import org.multipaz.verification.VerificationUtil
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.*
import web.html.InputType
import kotlin.random.Random

// UI Data Models for Request Configuration
private data class DataElementUiModel(
    val name: String,
    var intentToRetain: Boolean
)

private data class NamespaceUiModel(
    var name: String,
    val elements: MutableList<DataElementUiModel>
)

private data class MappingUiModel(
    var dataElementName: String,
    var jsonPathStr: String
)

private data class ElementReferenceUiModel(
    var namespace: String = "org.iso.18013.5.1",
    var elementIdentifier: String = ""
)

private data class AlternativeSetUiModel(
    val elements: MutableList<ElementReferenceUiModel> = mutableListOf()
)

private data class AlternativeDataElementSetUiModel(
    val requestedElement: ElementReferenceUiModel = ElementReferenceUiModel(namespace = "org.iso.18013.5.1", elementIdentifier = "given_name"),
    val alternativeSets: MutableList<AlternativeSetUiModel> = mutableListOf()
)

private data class ReaderKeyUiModel(
    val id: String,
    var name: String,
    var curve: EcCurve = EcCurve.P256,
    var privateKey: EcPrivateKey,
    var certChain: X509CertChain? = null,
    var useInReaderAuthAll: Boolean = true,
    var inputText: String = "",
    var inputError: String = "",
    var showImport: Boolean = false
)

private data class DocRequestUiModel(
    val id: String,
    var docType: String,
    val namespaces: MutableList<NamespaceUiModel>,
    var docRequestInfoEnabled: Boolean = false,
    var docFormat: String = "",
    var uniqueDocSetRequired: String = "default", // "default", "true", "false"
    var maximumResponseSize: String = "",
    var zkRequestEnabled: Boolean = false,
    var zkRequired: Boolean = false,
    val selectedZkCircuitIds: MutableSet<String> = mutableSetOf(),
    var issuerIdentifiersHex: String = "",
    val alternativeDataElements: MutableList<AlternativeDataElementSetUiModel> = mutableListOf(),
    val dataElementIdentifierMapping: MutableList<MappingUiModel> = mutableListOf(),
    // Reader Auth options
    var readerAuthMode: String = "none", // "none", "shared", "dedicated"
    var selectedSharedKeyId: String = "",
    var dedicatedCurve: EcCurve = EcCurve.P256,
    var dedicatedPrivateKey: EcPrivateKey? = null,
    var dedicatedCertChain: X509CertChain? = null,
    var dedicatedInputText: String = "",
    var dedicatedInputError: String = "",
    var showDedicatedImport: Boolean = false
)

private suspend fun createX509CertifiedKey(
    privateKey: EcPrivateKey,
    existingCertChain: X509CertChain?
): AsymmetricKey.X509Compatible {
    val certChain = if (existingCertChain != null && existingCertChain.certificates.isNotEmpty()) {
        existingCertChain
    } else {
        val now = Clock.System.now()
        val rootKey = Crypto.createEcPrivateKey(privateKey.curve)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("CN=Multipaz Reader Root,C=US"),
            serial = ASN1Integer(1.toLong()),
            validFrom = now.minus(1.days),
            validUntil = now.plus(365.days),
            crlUrl = "http://example.com/crl"
        )
        val rootSigningKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = rootKey,
            certChain = X509CertChain(listOf(rootCert))
        )
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = rootSigningKey,
            readerKey = privateKey.publicKey,
            subject = X500Name.fromName("CN=Multipaz Reader Key,C=US"),
            dnsName = "localhost",
            serial = ASN1Integer(2.toLong()),
            validFrom = now.minus(1.days),
            validUntil = now.plus(365.days)
        )
        X509CertChain(listOf(readerCert, rootCert))
    }
    return AsymmetricKey.X509CertifiedExplicit(
        privateKey = privateKey,
        certChain = certChain
    )
}

private data class DocumentSetUiModel(
    val docRequestIds: MutableList<Int> = mutableListOf(0)
)

private data class PurposeHintUiModel(
    var namespace: String = "org.iso.jtc1.sc17",
    var code: Int = 1
)

private data class UseCaseUiModel(
    val id: String,
    var mandatory: Boolean = true,
    val documentSets: MutableList<DocumentSetUiModel> = mutableListOf(DocumentSetUiModel(mutableListOf(0))),
    val purposeHints: MutableList<PurposeHintUiModel> = mutableListOf()
)

val Iso180137VerifierComponent: FC<Props> = FC {
    // Document Type Repository
    val docTypeRepo = useState {
        DocumentTypeRepository().apply {
            addKnownTypes()
            addUtopiaTypes()
        }
    }.component1()

    // Supported Curves List (curves supporting signing operations)
    val supportedCurvesList = useState {
        Crypto.supportedCurves.filter { it.defaultSigningAlgorithm != Algorithm.UNSET }.sortedBy { it.name }
    }.component1()

    // Longfellow ZK Circuits List
    val longfellowCircuits = useState {
        LongfellowZkSystem().apply { addDefaultCircuits() }.systemSpecs
    }.component1()

    // HPKE Receiver Key & Nonce
    var hpkePrivateKey by useState<EcPrivateKey?>(null)
    var nonceHex by useState("")
    var origin by useState(window.location.origin)

    // Reader Authentication Keys Pool (for ReaderAuthAll and shared doc request signing)
    val readerKeys = useState {
        mutableListOf<ReaderKeyUiModel>()
    }.component1()

    // Canned Request Selection
    var selectedDocType by useState<DocumentType?>(null)
    var selectedCannedRequest by useState<SingleDocumentCannedRequest?>(null)
    var selectedCannedRequestFormat by useState("mdoc") // "mdoc" or "vc"

    // Load Encoded Input State
    var loadInputText by useState("")
    var loadError by useState("")

    // DeviceRequest Settings
    var forcedVersion by useState("auto") // "auto", "1.0", "1.1"
    var deviceRequestInfoEnabled by useState(false)
    val useCases = useState { mutableListOf<UseCaseUiModel>() }.component1()
    var useReaderAuthAll by useState(false)

    // Document Requests list
    val docRequests = useState {
        mutableListOf<DocRequestUiModel>()
    }.component1()

    // State map for docRequestId selection per useCase and documentSet: "ucId-dsIdx"
    var selectedDocReqInputs by useState<Map<String, Int>>(emptyMap())

    // Generated Request Output State
    var generatedW3cJson by useState("")
    var generatedDevReqHex by useState("")
    var generatedEncInfoHex by useState("")
    var generatedDevReqDataItem by useState<DataItem?>(null)
    var generatedEncInfoDataItem by useState<DataItem?>(null)
    var buildError by useState("")

    // Invocation & Response State
    var isInvoking by useState(false)
    var invokeError by useState("")
    var decryptedResponse by useState<MdocApiDcResponse?>(null)
    var decryptedDevReqObj by useState<DeviceResponse?>(null)
    var decryptedDevReqHex by useState("")
    var verificationError by useState("")

    // Copy Feedback
    var copyStatus by useState("")

    // Map for inline element input values keyed by "docReqId-nsIdx"
    var newElemInputs by useState<Map<String, String>>(emptyMap())

    // Function to add a new document request from selected canned request
    fun addCannedDocRequest(cannedReq: SingleDocumentCannedRequest, format: String) {
        val newDocReq = if (format == "vc") {
            val jsonReq = cannedReq.jsonRequest ?: return
            val elementsUi = mutableListOf<DataElementUiModel>()
            val mappingsUi = mutableListOf<MappingUiModel>()

            for (claim in jsonReq.claimsToRequest) {
                val pathComponents = buildList {
                    claim.parentAttribute?.let { add(it.identifier) }
                    add(claim.identifier)
                }
                val elemName = pathComponents.joinToString(".")
                val jsonPathStr = Json.encodeToString(
                    kotlinx.serialization.json.JsonArray.serializer(),
                    kotlinx.serialization.json.buildJsonArray { pathComponents.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                )
                elementsUi.add(DataElementUiModel(name = elemName, intentToRetain = false))
                mappingsUi.add(MappingUiModel(dataElementName = elemName, jsonPathStr = jsonPathStr))
            }

            val nsUi = NamespaceUiModel(
                name = "_",
                elements = elementsUi
            )

            DocRequestUiModel(
                id = "doc-req-${Random.nextInt(10000, 99999)}",
                docType = jsonReq.vct,
                namespaces = mutableListOf(nsUi),
                docRequestInfoEnabled = true,
                docFormat = "sd-jwt+kb",
                dataElementIdentifierMapping = mappingsUi,
                selectedSharedKeyId = readerKeys.firstOrNull()?.id ?: ""
            )
        } else {
            val mdocReq = cannedReq.mdocRequest ?: return
            val newDocReq = DocRequestUiModel(
                id = "doc-req-${Random.nextInt(10000, 99999)}",
                docType = mdocReq.docType,
                namespaces = mutableListOf(),
                docRequestInfoEnabled = mdocReq.useZkp,
                zkRequestEnabled = mdocReq.useZkp,
                zkRequired = mdocReq.useZkp,
                selectedSharedKeyId = readerKeys.firstOrNull()?.id ?: ""
            )

            for (nsReq in mdocReq.namespacesToRequest) {
                val nsUi = NamespaceUiModel(
                    name = nsReq.namespace,
                    elements = mutableListOf()
                )
                for ((elem, intent) in nsReq.dataElementsToRequest) {
                    nsUi.elements.add(DataElementUiModel(elem.attribute.identifier, intent))
                }
                newDocReq.namespaces.add(nsUi)
            }
            newDocReq
        }

        docRequests.add(newDocReq)
    }

    // Function to reset verifier state to clean slate
    fun resetAll() {
        mainScope.launch {
            docRequests.clear()
            useCases.clear()
            deviceRequestInfoEnabled = false
            forcedVersion = "auto"
            useReaderAuthAll = false
            hpkePrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)

            readerKeys.clear()
            val freshKey = Crypto.createEcPrivateKey(EcCurve.P256)
            readerKeys.add(
                ReaderKeyUiModel(
                    id = "reader-key-1",
                    name = "Reader Key #1",
                    curve = EcCurve.P256,
                    privateKey = freshKey,
                    useInReaderAuthAll = true
                )
            )

            nonceHex = Random.nextBytes(16).toHex()
            newElemInputs = emptyMap()
            selectedDocReqInputs = emptyMap()
            loadInputText = ""
            loadError = ""
            generatedW3cJson = ""
            generatedDevReqHex = ""
            generatedEncInfoHex = ""
            generatedDevReqDataItem = null
            generatedEncInfoDataItem = null
            buildError = ""
            isInvoking = false
            invokeError = ""
            decryptedResponse = null
            decryptedDevReqObj = null
            decryptedDevReqHex = ""
            verificationError = ""
        }
    }

    // Initialize Keys and Nonce once
    useEffectOnce {
        mainScope.launch {
            hpkePrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)

            if (readerKeys.isEmpty()) {
                val initialReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
                readerKeys.add(
                    ReaderKeyUiModel(
                        id = "reader-key-1",
                        name = "Reader Key #1",
                        curve = EcCurve.P256,
                        privateKey = initialReaderKey,
                        useInReaderAuthAll = true
                    )
                )
            }

            val randomNonce = Random.nextBytes(16).toHex()
            nonceHex = randomNonce

            val dlType = docTypeRepo.documentTypes.find { it.mdocDocumentType?.docType == "org.iso.18013.5.1.mDL" }
                ?: docTypeRepo.documentTypes.firstOrNull()
            if (dlType != null) {
                selectedDocType = dlType
                val firstCanned = dlType.cannedRequests.firstOrNull()
                selectedCannedRequest = firstCanned
                if (firstCanned?.mdocRequest != null) {
                    selectedCannedRequestFormat = "mdoc"
                } else if (firstCanned?.jsonRequest != null) {
                    selectedCannedRequestFormat = "vc"
                }
            }
        }
    }

    // Build W3C Digital Credentials Request JSON
    fun rebuildRequest() {
        mainScope.launch {
            try {
                buildError = ""
                val key = hpkePrivateKey ?: error("HPKE Receiver Key not generated yet")
                if (nonceHex.isBlank()) error("Nonce cannot be blank")

                val nonceBytes = nonceHex.fromHex()
                val responseEncryptionKey = key.publicKey

                // Build EncryptionInfo CBOR
                val encryptionInfoDataItem = buildCborArray {
                    add("dcapi")
                    addCborMap {
                        put("nonce", nonceBytes)
                        put("recipientPublicKey", responseEncryptionKey.toCoseKey().toDataItem())
                    }
                }
                val base64EncryptionInfo = Cbor.encode(encryptionInfoDataItem).toBase64Url()
                val encInfoHexStr = Cbor.encode(encryptionInfoDataItem).toHex()

                // Session Transcript for DC API
                val dcapiInfo = buildCborArray {
                    add(base64EncryptionInfo)
                    add(origin)
                }
                val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(dcapiInfoDigest)
                    }
                }

                // DeviceRequestInfo
                val devReqInfo = if (deviceRequestInfoEnabled) {
                    val ucList = useCases.map { uc ->
                        val docSets = uc.documentSets.map { ds ->
                            DocumentSet(ds.docRequestIds)
                        }
                        val hints = uc.purposeHints.associate { p -> p.namespace to p.code }
                        UseCase(
                            mandatory = uc.mandatory,
                            documentSets = docSets,
                            purposeHints = hints
                        )
                    }
                    DeviceRequestInfo.fromValues(useCases = ucList)
                } else null

                val versionToUse = when (forcedVersion) {
                    "1.0" -> "1.0"
                    "1.1" -> "1.1"
                    else -> null
                }

                val builder = DeviceRequest.Builder(
                    sessionTranscript = sessionTranscript,
                    deviceRequestInfo = devReqInfo,
                    version = versionToUse
                )

                for (docReq in docRequests) {
                    val itemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
                    for (ns in docReq.namespaces) {
                        val elemMap = mutableMapOf<String, Boolean>()
                        for (elem in ns.elements) {
                            elemMap[elem.name] = elem.intentToRetain
                        }
                        if (elemMap.isNotEmpty()) {
                            itemsToRequest[ns.name] = elemMap
                        }
                    }

                    val docReqInfo = if (docReq.docRequestInfoEnabled) {
                        val zkReq = if (docReq.zkRequestEnabled) {
                            val selectedSpecs = if (docReq.selectedZkCircuitIds.isEmpty()) {
                                longfellowCircuits
                            } else {
                                longfellowCircuits.filter { it.id in docReq.selectedZkCircuitIds }
                            }
                            ZkRequest(systemSpecs = selectedSpecs, zkRequired = docReq.zkRequired)
                        } else null

                        val mapping = mutableMapOf<String, JsonArray>()
                        for (m in docReq.dataElementIdentifierMapping) {
                            try {
                                if (m.dataElementName.isNotBlank() && m.jsonPathStr.isNotBlank()) {
                                    val jsonPath = Json.parseToJsonElement(m.jsonPathStr).jsonArray
                                    mapping[m.dataElementName] = jsonPath
                                }
                            } catch (e: Throwable) {
                                // ignore invalid path JSON
                            }
                        }

                        val uniqueDoc = when (docReq.uniqueDocSetRequired) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        }
                        val maxResp = docReq.maximumResponseSize.toLongOrNull()

                        val issuerIds = docReq.issuerIdentifiersHex.split(",").mapNotNull {
                            val trimmed = it.trim()
                            if (trimmed.isNotBlank()) {
                                try { ByteString(trimmed.fromHex()) } catch (e: Throwable) { null }
                            } else null
                        }

                        val altElements = docReq.alternativeDataElements.mapNotNull { altSetUi ->
                            val reqNs = altSetUi.requestedElement.namespace.trim()
                            val reqElem = altSetUi.requestedElement.elementIdentifier.trim()
                            if (reqNs.isNotBlank() && reqElem.isNotBlank()) {
                                val reqRef = ElementReference(reqNs, reqElem)
                                val altSets = altSetUi.alternativeSets.mapNotNull { setUi ->
                                    val refs = setUi.elements.mapNotNull { elemUi ->
                                        val ns = elemUi.namespace.trim()
                                        val elem = elemUi.elementIdentifier.trim()
                                        if (ns.isNotBlank() && elem.isNotBlank()) {
                                            ElementReference(ns, elem)
                                        } else null
                                    }
                                    if (refs.isNotEmpty()) refs else null
                                }
                                if (altSets.isNotEmpty()) {
                                    AlternativeDataElementSet(
                                        requestedElement = reqRef,
                                        alternativeElementSets = altSets
                                    )
                                } else null
                            } else null
                        }

                        DocRequestInfo(
                            alternativeDataElements = altElements,
                            issuerIdentifiers = issuerIds,
                            uniqueDocSetRequired = uniqueDoc,
                            maximumResponseSize = maxResp,
                            zkRequest = zkReq,
                            docFormat = if (docReq.docFormat.isNotBlank()) docReq.docFormat else null,
                            dataElementIdentifierMapping = mapping
                        )
                    } else null

                    // Determine Reader Key for DocRequest
                    val readerKeyToUse: AsymmetricKey.X509Compatible? = when (docReq.readerAuthMode) {
                        "shared" -> {
                            val keyModel = readerKeys.find { it.id == docReq.selectedSharedKeyId } ?: readerKeys.firstOrNull()
                            keyModel?.let { createX509CertifiedKey(it.privateKey, it.certChain) }
                        }
                        "dedicated" -> {
                            docReq.dedicatedPrivateKey?.let { createX509CertifiedKey(it, docReq.dedicatedCertChain) }
                        }
                        else -> null
                    }

                    if (readerKeyToUse != null) {
                        builder.addDocRequest(
                            docType = docReq.docType,
                            nameSpaces = itemsToRequest,
                            docRequestInfo = docReqInfo,
                            readerKey = readerKeyToUse
                        )
                    } else {
                        builder.addDocRequest(
                            docType = docReq.docType,
                            nameSpaces = itemsToRequest,
                            docRequestInfo = docReqInfo
                        )
                    }
                }

                // Add ReaderAuthAll for all enabled keys in readerKeys pool if section enabled
                if (useReaderAuthAll) {
                    for (keyModel in readerKeys) {
                        if (keyModel.useInReaderAuthAll) {
                            val asymKey = createX509CertifiedKey(keyModel.privateKey, keyModel.certChain)
                            builder.addReaderAuthAll(asymKey)
                        }
                    }
                }

                val devReqObj = builder.build()
                val devReqDataItem = devReqObj.toDataItem()
                val devReqBytes = Cbor.encode(devReqDataItem)
                val base64DeviceRequest = devReqBytes.toBase64Url()
                val devReqHexStr = devReqBytes.toHex()

                val w3cReqJsonObj = buildJsonObject {
                    putJsonObject("digital") {
                        putJsonArray("requests") {
                            addJsonObject {
                                put("protocol", "org-iso-mdoc")
                                putJsonObject("data") {
                                    put("deviceRequest", base64DeviceRequest)
                                    put("encryptionInfo", base64EncryptionInfo)
                                }
                            }
                        }
                    }
                }

                generatedW3cJson = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), w3cReqJsonObj)
                generatedDevReqHex = devReqHexStr
                generatedEncInfoHex = encInfoHexStr
                generatedDevReqDataItem = devReqDataItem
                generatedEncInfoDataItem = encryptionInfoDataItem
            } catch (e: Throwable) {
                buildError = "Error building DeviceRequest: ${e.message ?: e.toString()}"
                generatedW3cJson = ""
                generatedDevReqHex = ""
                generatedEncInfoHex = ""
            }
        }
    }

    // Function to parse and load encoded DeviceRequest payload into UI
    fun loadDeviceRequestFromInput(inputStr: String) {
        try {
            loadError = ""
            val trimmed = inputStr.trim()
            if (trimmed.isBlank()) error("Input payload is empty")

            val bytes: ByteArray = when {
                trimmed.startsWith("{") -> {
                    val jsonObj = Json.parseToJsonElement(trimmed).jsonObject
                    val devReqBase64 = if (jsonObj.containsKey("digital")) {
                        jsonObj["digital"]!!.jsonObject["requests"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
                    } else if (jsonObj.containsKey("deviceRequest")) {
                        jsonObj["deviceRequest"]!!.jsonPrimitive.content
                    } else {
                        error("Could not find 'deviceRequest' in JSON structure")
                    }
                    devReqBase64.fromBase64Url()
                }
                trimmed.all { it in "0123456789abcdefABCDEF" } && trimmed.length % 2 == 0 -> {
                    trimmed.fromHex()
                }
                else -> {
                    trimmed.fromBase64Url()
                }
            }

            val devReqDataItem = Cbor.decode(bytes)
            val devReq = DeviceRequest.fromDataItem(devReqDataItem)

            forcedVersion = devReq.version
            useReaderAuthAll = try { devReq.readerAuthAll.isNotEmpty() } catch (e: Throwable) { false }

            if (devReq.deviceRequestInfo != null) {
                deviceRequestInfoEnabled = true
                useCases.clear()
                devReq.deviceRequestInfo!!.useCases.forEachIndexed { idx, uc ->
                    val docSetsUi = uc.documentSets.map { ds ->
                        DocumentSetUiModel(ds.docRequestIds.toMutableList())
                    }.toMutableList()
                    val hintsUi = uc.purposeHints.map { (ns, code) ->
                        PurposeHintUiModel(ns, code)
                    }.toMutableList()
                    useCases.add(
                        UseCaseUiModel(
                            id = "uc-${Random.nextInt(10000, 99999)}",
                            mandatory = uc.mandatory,
                            documentSets = docSetsUi,
                            purposeHints = hintsUi
                        )
                    )
                }
            } else {
                deviceRequestInfoEnabled = false
            }

            docRequests.clear()
            devReq.docRequests.forEachIndexed { docIdx, dr ->
                val nsList = mutableListOf<NamespaceUiModel>()
                for ((nsName, elemMap) in dr.nameSpaces) {
                    val elemList = mutableListOf<DataElementUiModel>()
                    for ((elemName, intent) in elemMap) {
                        elemList.add(DataElementUiModel(elemName, intent))
                    }
                    nsList.add(NamespaceUiModel(nsName, elemList))
                }

                val dri = dr.docRequestInfo
                val docReqInfoEnabled = (dri != null)
                val formatStr = dri?.docFormat ?: ""
                val uniqueDocStr = when (dri?.uniqueDocSetRequired) {
                    true -> "true"
                    false -> "false"
                    else -> "default"
                }
                val maxRespStr = dri?.maximumResponseSize?.toString() ?: ""
                val zkReqEnabled = (dri?.zkRequest != null)
                val zkReqMandatory = dri?.zkRequest?.zkRequired ?: false
                val selectedCircuitIds = mutableSetOf<String>()
                dri?.zkRequest?.systemSpecs?.forEach { spec ->
                    selectedCircuitIds.add(spec.id)
                }
                val issuerHex = dri?.issuerIdentifiers?.joinToString(", ") { it.toByteArray().toHex() } ?: ""

                val altElementsUi = mutableListOf<AlternativeDataElementSetUiModel>()
                dri?.alternativeDataElements?.forEach { altSet ->
                    val reqRef = ElementReferenceUiModel(altSet.requestedElement.namespace, altSet.requestedElement.dataElement)
                    val altSetsUi = mutableListOf<AlternativeSetUiModel>()
                    altSet.alternativeElementSets.forEach { setOfRefs ->
                        val refsUi = setOfRefs.map { ref -> ElementReferenceUiModel(ref.namespace, ref.dataElement) }.toMutableList()
                        altSetsUi.add(AlternativeSetUiModel(refsUi))
                    }
                    altElementsUi.add(AlternativeDataElementSetUiModel(reqRef, altSetsUi))
                }

                val mappingUi = mutableListOf<MappingUiModel>()
                dri?.dataElementIdentifierMapping?.forEach { (elemName, jsonPathArray) ->
                    mappingUi.add(MappingUiModel(elemName, jsonPathArray.toString()))
                }

                val hasDocReaderAuth = try { dr.readerAuth != null } catch (e: Throwable) { false }

                val docReqUi = DocRequestUiModel(
                    id = "doc-req-${Random.nextInt(10000, 99999)}",
                    docType = dr.docType,
                    namespaces = nsList,
                    docRequestInfoEnabled = docReqInfoEnabled,
                    docFormat = formatStr,
                    uniqueDocSetRequired = uniqueDocStr,
                    maximumResponseSize = maxRespStr,
                    zkRequestEnabled = zkReqEnabled,
                    zkRequired = zkReqMandatory,
                    selectedZkCircuitIds = selectedCircuitIds,
                    issuerIdentifiersHex = issuerHex,
                    alternativeDataElements = altElementsUi,
                    dataElementIdentifierMapping = mappingUi,
                    readerAuthMode = if (hasDocReaderAuth) "shared" else "none",
                    selectedSharedKeyId = readerKeys.firstOrNull()?.id ?: ""
                )

                docRequests.add(docReqUi)
            }

            // Rebuild W3C JSON automatically
            rebuildRequest()
        } catch (e: Throwable) {
            loadError = "Failed to parse DeviceRequest: ${e.message ?: e.toString()}"
        }
    }

    // Function to parse and set custom Reader Private Key (JWK or COSE Key Hex) for a ReaderKeyUiModel
    fun loadCustomReaderKey(keyModel: ReaderKeyUiModel, inputStr: String) {
        try {
            keyModel.inputError = ""
            val trimmed = inputStr.trim()
            if (trimmed.isBlank()) error("Reader Key input is empty")

            val parsedKey: EcPrivateKey = when {
                trimmed.startsWith("{") -> {
                    val jwkObj = Json.parseToJsonElement(trimmed).jsonObject
                    EcPrivateKey.fromJwk(jwkObj)
                }
                trimmed.all { it in "0123456789abcdefABCDEF" } && trimmed.length % 2 == 0 -> {
                    val coseKeyDataItem = Cbor.decode(trimmed.fromHex())
                    EcPrivateKey.fromCoseKey(CoseKey.fromDataItem(coseKeyDataItem))
                }
                else -> {
                    error("Invalid format: expected JWK JSON or COSE Key CBOR hex")
                }
            }

            keyModel.privateKey = parsedKey
            keyModel.curve = parsedKey.curve
            keyModel.name = "Reader Key (imported)"
            keyModel.inputText = ""
            keyModel.showImport = false
            rebuildRequest()
        } catch (e: Throwable) {
            keyModel.inputError = "Failed to parse Reader Key: ${e.message ?: e.toString()}"
        }
    }

    // Function to parse and set custom dedicated Reader Private Key for a DocRequestUiModel
    fun loadCustomDedicatedReaderKey(docReq: DocRequestUiModel, inputStr: String) {
        try {
            docReq.dedicatedInputError = ""
            val trimmed = inputStr.trim()
            if (trimmed.isBlank()) error("Dedicated key input is empty")

            val parsedKey: EcPrivateKey = when {
                trimmed.startsWith("{") -> {
                    val jwkObj = Json.parseToJsonElement(trimmed).jsonObject
                    EcPrivateKey.fromJwk(jwkObj)
                }
                trimmed.all { it in "0123456789abcdefABCDEF" } && trimmed.length % 2 == 0 -> {
                    val coseKeyDataItem = Cbor.decode(trimmed.fromHex())
                    EcPrivateKey.fromCoseKey(CoseKey.fromDataItem(coseKeyDataItem))
                }
                else -> {
                    error("Invalid format: expected JWK JSON or COSE Key CBOR hex")
                }
            }

            docReq.dedicatedPrivateKey = parsedKey
            docReq.dedicatedCurve = parsedKey.curve
            docReq.dedicatedInputText = ""
            docReq.showDedicatedImport = false
            rebuildRequest()
        } catch (e: Throwable) {
            docReq.dedicatedInputError = "Failed to parse Dedicated Key: ${e.message ?: e.toString()}"
        }
    }

    // Auto-rebuild request on state updates
    useEffect(
        hpkePrivateKey, readerKeys.size, nonceHex, origin, forcedVersion, deviceRequestInfoEnabled,
        useReaderAuthAll, docRequests.size
    ) {
        if (hpkePrivateKey != null && docRequests.isNotEmpty()) {
            rebuildRequest()
        }
    }

    // Decrypt a raw response object
    suspend fun decryptResponseObject(responseObj: JsonObject) {
        try {
            invokeError = ""
            val key = hpkePrivateKey ?: error("No HPKE private key available")
            val nonceBytes = nonceHex.fromHex()

            val dcResponse = VerificationUtil.decryptDcResponse(
                response = responseObj,
                nonce = ByteString(nonceBytes),
                origin = origin,
                responseEncryptionKey = AsymmetricKey.anonymous(key)
            )

            if (dcResponse !is MdocApiDcResponse) {
                error("Expected MdocApiDcResponse for org-iso-mdoc protocol")
            }

            val rawDevRespBytes = Cbor.encode(dcResponse.deviceResponse)
            val devRespObj = DeviceResponse.fromDataItem(dcResponse.deviceResponse)

            var vErr = ""
            try {
                devRespObj.verify(
                    sessionTranscript = dcResponse.sessionTranscript
                )
            } catch (e: Throwable) {
                vErr = e.message ?: e.toString()
            }

            verificationError = vErr
            decryptedResponse = dcResponse
            decryptedDevReqObj = devRespObj
            decryptedDevReqHex = rawDevRespBytes.toHex()
        } catch (e: Throwable) {
            invokeError = "Error decrypting response: ${e.message ?: e.toString()}"
            verificationError = ""
            decryptedResponse = null
            decryptedDevReqObj = null
            decryptedDevReqHex = ""
        }
    }

    // Main Component Render
    div {
        css {
            background = Color("#1e293b")
            borderRadius = 16.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 32.px
        }

        div {
            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 16.px }

            h2 {
                css {
                    fontSize = 1.8.rem
                    fontWeight = FontWeight.bold
                    margin = 0.px
                    color = Color("#f8fafc")
                }
                +"ISO 18013-7 Verifier (Client-Side W3C DC API)"
            }

            button {
                css {
                    background = Color("#334155")
                    color = Color("#f87171")
                    border = Border(1.px, LineStyle.solid, Color("#991b1b"))
                    borderRadius = 8.px
                    padding = Padding(8.px, 16.px)
                    fontWeight = FontWeight.bold
                    cursor = Cursor.pointer
                    fontSize = 13.px
                    hover { background = Color("#991b1b"); color = Color("#ffffff") }
                }
                onClick = { resetAll() }
                +"🧹 Reset Verifier"
            }
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Build ISO 18013-7 Annex C DeviceRequest payloads, configure HPKE keys & nonces, request mdoc credentials via navigator.credentials.get(), and decrypt responses 100% client-side."
        }

        // Section: Load Encoded DeviceRequest Input Card
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#a78bfa"); marginTop = 0.px; marginBottom = 12.px }
                +"📥 Load Encoded DeviceRequest into Verifier UI"
            }

            p {
                css { color = Color("#94a3b8"); fontSize = 13.px; marginBottom = 12.px }
                +"Paste an encoded DeviceRequest in Hex, Base64Url, or W3C Digital Credentials Request JSON format below to populate all verifier settings."
            }

            textarea {
                css {
                    width = 100.pct
                    height = 80.px
                    background = Color("#1e293b")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#f1f5f9")
                    fontFamily = FontFamily.monospace
                    fontSize = 12.px
                    padding = 10.px
                    resize = "none".unsafeCast<Resize>()
                    marginBottom = 12.px
                }
                placeholder = "Paste Hex (e.g. A26776...), Base64Url (e.g. eyJ...), or W3C Request JSON..."
                value = loadInputText
                onChange = { ev -> loadInputText = ev.target.value }
            }

            div {
                css { display = Display.flex; gap = 12.px; alignItems = AlignItems.center }

                button {
                    css {
                        background = Color("#7c3aed")
                        color = Color("#ffffff")
                        border = None.none
                        padding = Padding(8.px, 20.px)
                        borderRadius = 6.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        fontSize = 13.px
                        hover { background = Color("#6d28d9") }
                    }
                    onClick = { loadDeviceRequestFromInput(loadInputText) }
                    +"📥 Load into Verifier UI"
                }

                if (loadInputText.isNotBlank()) {
                    button {
                        css {
                            background = Color("#334155")
                            color = Color("#cbd5e1")
                            border = None.none
                            padding = Padding(8.px, 14.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#475569") }
                        }
                        onClick = { loadInputText = ""; loadError = "" }
                        +"Clear Input"
                    }
                }
            }

            if (loadError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 12.px
                        padding = 10.px
                        background = Color("#451a1a")
                        border = Border(1.px, LineStyle.solid, Color("#f87171"))
                        borderRadius = 6.px
                        color = Color("#fca5a5")
                        fontWeight = FontWeight.bold
                        fontSize = 12.px
                    }
                    +loadError
                }
            }
        }

        // Section: Canned Request Preset Picker
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 12.px }
                +"⚡ Canned Request Presets (multipaz-doctypes)"
            }

            div {
                css { display = Display.flex; gap = 16.px; alignItems = AlignItems.center; flexWrap = FlexWrap.wrap }

                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                    label { css { fontSize = 12.px; fontWeight = FontWeight.bold; color = Color("#94a3b8") }; +"Document Type:" }
                    select {
                        css {
                            background = Color("#1e293b")
                            color = Color("#f1f5f9")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            padding = Padding(8.px, 12.px)
                            fontSize = 14.px
                        }
                        value = selectedDocType?.displayName ?: ""
                        onChange = { ev ->
                            val docTypeObj = docTypeRepo.documentTypes.find { it.displayName == ev.target.value }
                            selectedDocType = docTypeObj
                            val firstCanned = docTypeObj?.cannedRequests?.firstOrNull()
                            selectedCannedRequest = firstCanned
                            if (firstCanned?.mdocRequest != null) {
                                selectedCannedRequestFormat = "mdoc"
                            } else if (firstCanned?.jsonRequest != null) {
                                selectedCannedRequestFormat = "vc"
                            }
                        }
                        for (dt in docTypeRepo.documentTypes) {
                            option {
                                value = dt.displayName
                                +dt.displayName
                            }
                        }
                    }
                }

                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                    label { css { fontSize = 12.px; fontWeight = FontWeight.bold; color = Color("#94a3b8") }; +"Sample Request:" }
                    select {
                        css {
                            background = Color("#1e293b")
                            color = Color("#f1f5f9")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            padding = Padding(8.px, 12.px)
                            fontSize = 14.px
                        }
                        value = selectedCannedRequest?.id ?: ""
                        onChange = { ev ->
                            val cannedObj = selectedDocType?.cannedRequests?.find { it.id == ev.target.value }
                            selectedCannedRequest = cannedObj
                            if (selectedCannedRequestFormat == "mdoc" && cannedObj?.mdocRequest == null && cannedObj?.jsonRequest != null) {
                                selectedCannedRequestFormat = "vc"
                            } else if (selectedCannedRequestFormat == "vc" && cannedObj?.jsonRequest == null && cannedObj?.mdocRequest != null) {
                                selectedCannedRequestFormat = "mdoc"
                            }
                        }
                        selectedDocType?.cannedRequests?.forEach { req ->
                            option {
                                value = req.id
                                +req.displayName
                            }
                        }
                    }
                }

                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                    label { css { fontSize = 12.px; fontWeight = FontWeight.bold; color = Color("#94a3b8") }; +"Format:" }
                    select {
                        css {
                            background = Color("#1e293b")
                            color = Color("#f1f5f9")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            padding = Padding(8.px, 12.px)
                            fontSize = 14.px
                        }
                        value = selectedCannedRequestFormat
                        onChange = { ev ->
                            selectedCannedRequestFormat = ev.target.value
                        }
                        option {
                            value = "mdoc"
                            disabled = (selectedCannedRequest?.mdocRequest == null)
                            +"ISO mdoc"
                        }
                        option {
                            value = "vc"
                            disabled = (selectedCannedRequest?.jsonRequest == null)
                            +"SD-JWT VC"
                        }
                    }
                }

                button {
                    css {
                        marginTop = 18.px
                        padding = Padding(10.px, 20.px)
                        background = Color("#2563eb")
                        color = Color("#ffffff")
                        border = None.none
                        borderRadius = 8.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        hover { background = Color("#1d4ed8") }
                    }
                    onClick = {
                        val cr = selectedCannedRequest
                        if (cr != null) {
                            addCannedDocRequest(cr, selectedCannedRequestFormat)
                            rebuildRequest()
                        }
                    }
                    +"⚡ Add Canned Document Request"
                }
            }
        }

        // Section: Session Controls (HPKE Receiver Key & Nonce)
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            h3 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); marginTop = 0.px; marginBottom = 12.px }
                +"🔑 HPKE Key & Session Parameters"
            }

            div {
                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 14.px; fontSize = 14.px }

                // HPKE Key Controls
                div {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px; flexWrap = FlexWrap.wrap }
                    span { css { fontWeight = FontWeight.bold; color = Color("#cbd5e1") }; +"HPKE Curve: " }
                    span { css { color = Color("#4ade80"); fontWeight = FontWeight.bold }; +"P-256 (only supported curve)" }

                    button {
                        css {
                            background = Color("#334155")
                            color = Color("#f1f5f9")
                            border = None.none
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#475569") }
                        }
                        onClick = {
                            mainScope.launch {
                                hpkePrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
                                rebuildRequest()
                            }
                        }
                        +"🔄 Regenerate HPKE Key"
                    }
                }

                // Nonce & Origin
                div {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 16.px }
                    label { css { fontWeight = FontWeight.bold; color = Color("#cbd5e1") }; +"Nonce (16 bytes hex):" }
                    input {
                        css {
                            background = Color("#1e293b")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#38bdf8")
                            fontFamily = FontFamily.monospace
                            padding = Padding(6.px, 10.px)
                            width = 300.px
                        }
                        value = nonceHex
                        onChange = { ev ->
                            nonceHex = ev.target.value.trim()
                        }
                    }

                    button {
                        css {
                            background = Color("#334155")
                            color = Color("#f1f5f9")
                            border = None.none
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#475569") }
                        }
                        onClick = {
                            nonceHex = Random.nextBytes(16).toHex()
                            rebuildRequest()
                        }
                        +"🎲 Randomize Nonce"
                    }
                }

                div {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 16.px }
                    label { css { fontWeight = FontWeight.bold; color = Color("#cbd5e1") }; +"Origin:" }
                    input {
                        css {
                            background = Color("#1e293b")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f1f5f9")
                            padding = Padding(6.px, 10.px)
                            width = 300.px
                        }
                        value = origin
                        onChange = { ev ->
                            origin = ev.target.value.trim()
                        }
                    }
                }

                div {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 16.px }
                    label { css { fontWeight = FontWeight.bold; color = Color("#cbd5e1") }; +"Force DeviceRequest Version:" }
                    select {
                        css {
                            background = Color("#1e293b")
                            color = Color("#f1f5f9")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            padding = Padding(6.px, 10.px)
                        }
                        value = forcedVersion
                        onChange = { ev ->
                            forcedVersion = ev.target.value
                        }
                        option { value = "auto"; +"Auto-Determine (Default)" }
                        option { value = "1.0"; +"Force Version 1.0" }
                        option { value = "1.1"; +"Force Version 1.1" }
                    }
                }
            }
        }

        // Section: Reader Authentication Keys Pool & ReaderAuthAll Configuration
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            div {
                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }
                h3 { css { fontSize = 1.2.rem; color = Color("#a78bfa"); margin = 0.px }; +"🔐 Reader Authentication Keys (${readerKeys.size})" }

                button {
                    css {
                        background = Color("#7c3aed")
                        color = Color("#ffffff")
                        border = None.none
                        padding = Padding(6.px, 14.px)
                        borderRadius = 6.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        fontSize = 12.px
                        hover { background = Color("#6d28d9") }
                    }
                    onClick = {
                        mainScope.launch {
                            val newKey = Crypto.createEcPrivateKey(EcCurve.P256)
                            val idx = readerKeys.size + 1
                            readerKeys.add(
                                ReaderKeyUiModel(
                                    id = "reader-key-${Random.nextInt(10000, 99999)}",
                                    name = "Reader Key #$idx",
                                    curve = EcCurve.P256,
                                    privateKey = newKey,
                                    useInReaderAuthAll = true
                                )
                            )
                            rebuildRequest()
                        }
                    }
                    +"+ Add Reader Key"
                }
            }

            // ReaderAuthAll Global Toggle
            label {
                css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); fontWeight = FontWeight.bold; cursor = Cursor.pointer; marginBottom = 16.px }
                input {
                    type = "checkbox".unsafeCast<InputType>()
                    checked = useReaderAuthAll
                    onChange = { ev ->
                        useReaderAuthAll = ev.target.checked
                        rebuildRequest()
                    }
                }
                +"🔐 Sign entire DeviceRequest with ReaderAuthAll (includes all checked keys below)"
            }

            // Reader Keys Pool List
            readerKeys.forEachIndexed { kIdx, kModel ->
                div {
                    css {
                        background = Color("#1e293b")
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        padding = 14.px
                        marginBottom = 12.px
                    }

                    div {
                        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 10.px }

                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px; flexWrap = FlexWrap.wrap }
                            input {
                                css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#a78bfa"); fontWeight = FontWeight.bold; padding = Padding(4.px, 8.px); borderRadius = 4.px; width = 200.px }
                                value = kModel.name
                                onChange = { ev -> kModel.name = ev.target.value; rebuildRequest() }
                            }

                            span { css { fontSize = 12.px; color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Curve:" }
                            select {
                                css { background = Color("#0f172a"); color = Color("#4ade80"); fontWeight = FontWeight.bold; border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 4.px; padding = Padding(4.px, 8.px) }
                                value = kModel.curve.name
                                onChange = { ev ->
                                    val newCurve = supportedCurvesList.find { it.name == ev.target.value } ?: EcCurve.P256
                                    kModel.curve = newCurve
                                    mainScope.launch {
                                        kModel.privateKey = Crypto.createEcPrivateKey(newCurve)
                                        rebuildRequest()
                                    }
                                }
                                for (c in supportedCurvesList) {
                                    option { value = c.name; +c.name }
                                }
                            }

                            button {
                                css { background = Color("#334155"); color = Color("#38bdf8"); border = None.none; padding = Padding(4.px, 10.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold; hover { background = Color("#475569") } }
                                onClick = {
                                    mainScope.launch {
                                        kModel.privateKey = Crypto.createEcPrivateKey(kModel.curve)
                                        rebuildRequest()
                                    }
                                }
                                +"🔄 Gen Key"
                            }

                            button {
                                css { background = Color("#334155"); color = Color("#a78bfa"); border = None.none; padding = Padding(4.px, 10.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold; hover { background = Color("#475569") } }
                                onClick = { kModel.showImport = !kModel.showImport }
                                +if (kModel.showImport) "Hide Import" else "📥 Import JWK/Hex"
                            }
                        }

                        if (readerKeys.size > 1) {
                            button {
                                css { background = Color("#991b1b"); color = Color("#fca5a5"); border = None.none; padding = Padding(4.px, 8.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; hover { background = Color("#b91c1c"); color = Color("#ffffff") } }
                                onClick = {
                                    readerKeys.removeAt(kIdx)
                                    rebuildRequest()
                                }
                                +"🗑️ Remove Key"
                            }
                        }
                    }

                    if (useReaderAuthAll) {
                        label {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; color = Color("#cbd5e1"); fontSize = 12.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                            input {
                                type = "checkbox".unsafeCast<InputType>()
                                checked = kModel.useInReaderAuthAll
                                onChange = { ev ->
                                    kModel.useInReaderAuthAll = ev.target.checked
                                    rebuildRequest()
                                }
                            }
                            +"Include this key in ReaderAuthAll signatures"
                        }
                    }

                    if (kModel.showImport) {
                        div {
                            css { marginTop = 8.px; display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px }
                            textarea {
                                css { width = 100.pct; height = 65.px; background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#38bdf8"); fontFamily = FontFamily.monospace; fontSize = 12.px; padding = 6.px; resize = "none".unsafeCast<Resize>() }
                                placeholder = "Paste JWK JSON or COSE Key CBOR Hex..."
                                value = kModel.inputText
                                onChange = { ev -> kModel.inputText = ev.target.value }
                            }
                            button {
                                css { background = Color("#7c3aed"); color = Color("#ffffff"); border = None.none; padding = Padding(4.px, 12.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold; width = 140.px }
                                onClick = { loadCustomReaderKey(kModel, kModel.inputText) }
                                +"📥 Set Reader Key"
                            }
                            if (kModel.inputError.isNotEmpty()) {
                                div { css { color = Color("#fca5a5"); fontSize = 11.px; fontWeight = FontWeight.bold }; +kModel.inputError }
                            }
                        }
                    }
                }
            }
        }

        // Section: Document Requests List (`DocRequest`)
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            div {
                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 16.px }
                h3 { css { fontSize = 1.2.rem; color = Color("#38bdf8"); margin = 0.px }; +"📄 Document Requests (${docRequests.size})" }

                button {
                    css {
                        background = Color("#16a34a")
                        color = Color("#ffffff")
                        border = None.none
                        padding = Padding(8.px, 16.px)
                        borderRadius = 6.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        fontSize = 13.px
                        hover { background = Color("#15803d") }
                    }
                    onClick = {
                        val newReq = DocRequestUiModel(
                            id = "doc-req-${Random.nextInt(10000, 99999)}",
                            docType = "org.iso.18013.5.1.mDL",
                            namespaces = mutableListOf(
                                NamespaceUiModel(
                                    name = "org.iso.18013.5.1",
                                    elements = mutableListOf(
                                        DataElementUiModel("given_name", false),
                                        DataElementUiModel("family_name", false),
                                        DataElementUiModel("portrait", false)
                                    )
                                )
                            ),
                            selectedSharedKeyId = readerKeys.firstOrNull()?.id ?: ""
                        )
                        docRequests.add(newReq)
                        rebuildRequest()
                    }
                    +"+ Add Document Request"
                }
            }

            if (docRequests.isEmpty()) {
                div {
                    css { color = Color("#64748b"); fontStyle = FontStyle.italic; padding = 16.px }
                    +"No document requests added. Select a canned request above, load an encoded payload, or click '+ Add Document Request'."
                }
            }

            docRequests.forEachIndexed { docIdx, docReq ->
                div {
                    css {
                        background = Color("#1e293b")
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        padding = 16.px
                        marginBottom = 16.px
                    }

                    div {
                        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }
                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px }
                            span { css { fontWeight = FontWeight.bold; color = Color("#a78bfa") }; +"Document #${docIdx + 1} (docRequestId: $docIdx)" }
                            label { css { fontSize = 13.px; color = Color("#cbd5e1"); fontWeight = FontWeight.bold }; +"docType:" }
                            input {
                                css {
                                    background = Color("#0f172a")
                                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                                    borderRadius = 6.px
                                    color = Color("#38bdf8")
                                    fontFamily = FontFamily.monospace
                                    padding = Padding(4.px, 8.px)
                                    width = 240.px
                                }
                                value = docReq.docType
                                onChange = { ev ->
                                    docReq.docType = ev.target.value
                                    rebuildRequest()
                                }
                            }
                        }

                        button {
                            css {
                                background = Color("#991b1b")
                                color = Color("#fca5a5")
                                border = None.none
                                padding = Padding(4.px, 10.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontSize = 12.px
                                hover { background = Color("#b91c1c"); color = Color("#ffffff") }
                            }
                            onClick = {
                                docRequests.removeAt(docIdx)
                                rebuildRequest()
                            }
                            +"🗑️ Remove Doc"
                        }
                    }

                    // Per-Document Reader Authentication Settings
                    div {
                        css {
                            background = Color("#0f172a")
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            padding = 12.px
                            marginBottom = 12.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }

                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px; flexWrap = FlexWrap.wrap }
                            span { css { color = Color("#cbd5e1"); fontSize = 12.px; fontWeight = FontWeight.bold }; +"🔐 Document Reader Authentication:" }
                            select {
                                css { background = Color("#1e293b"); color = Color("#f1f5f9"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 4.px; padding = Padding(4.px, 8.px); fontSize = 12.px }
                                value = docReq.readerAuthMode
                                onChange = { ev ->
                                    docReq.readerAuthMode = ev.target.value
                                    mainScope.launch {
                                        if (docReq.readerAuthMode == "dedicated" && docReq.dedicatedPrivateKey == null) {
                                            docReq.dedicatedPrivateKey = Crypto.createEcPrivateKey(docReq.dedicatedCurve)
                                        }
                                        rebuildRequest()
                                    }
                                }
                                option { value = "none"; +"Disabled (No Reader Auth)" }
                                option { value = "shared"; +"Use Key from Reader Keys Pool" }
                                option { value = "dedicated"; +"Use Dedicated Key for this Doc" }
                            }

                            if (docReq.readerAuthMode == "shared") {
                                select {
                                    css { background = Color("#1e293b"); color = Color("#a78bfa"); fontWeight = FontWeight.bold; border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 4.px; padding = Padding(4.px, 8.px); fontSize = 12.px }
                                    value = docReq.selectedSharedKeyId
                                    onChange = { ev ->
                                        docReq.selectedSharedKeyId = ev.target.value
                                        rebuildRequest()
                                    }
                                    for (kModel in readerKeys) {
                                        option {
                                            value = kModel.id
                                            +kModel.name
                                        }
                                    }
                                }
                            }
                        }

                        if (docReq.readerAuthMode == "dedicated") {
                            div {
                                css { display = Display.flex; alignItems = AlignItems.center; gap = 10.px; flexWrap = FlexWrap.wrap; marginTop = 4.px }
                                span { css { fontSize = 12.px; color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Dedicated Curve:" }
                                select {
                                    css { background = Color("#1e293b"); color = Color("#4ade80"); fontWeight = FontWeight.bold; border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 4.px; padding = Padding(2.px, 6.px); fontSize = 12.px }
                                    value = docReq.dedicatedCurve.name
                                    onChange = { ev ->
                                        val newCurve = supportedCurvesList.find { it.name == ev.target.value } ?: EcCurve.P256
                                        docReq.dedicatedCurve = newCurve
                                        mainScope.launch {
                                            docReq.dedicatedPrivateKey = Crypto.createEcPrivateKey(newCurve)
                                            rebuildRequest()
                                        }
                                    }
                                    for (c in supportedCurvesList) {
                                        option { value = c.name; +c.name }
                                    }
                                }

                                button {
                                    css { background = Color("#334155"); color = Color("#38bdf8"); border = None.none; padding = Padding(2.px, 8.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold }
                                    onClick = {
                                        mainScope.launch {
                                            docReq.dedicatedPrivateKey = Crypto.createEcPrivateKey(docReq.dedicatedCurve)
                                            rebuildRequest()
                                        }
                                    }
                                    +"🔄 Gen Dedicated Key"
                                }

                                button {
                                    css { background = Color("#334155"); color = Color("#a78bfa"); border = None.none; padding = Padding(2.px, 8.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold }
                                    onClick = { docReq.showDedicatedImport = !docReq.showDedicatedImport }
                                    +if (docReq.showDedicatedImport) "Hide Import" else "📥 Import JWK/Hex"
                                }
                            }

                            if (docReq.showDedicatedImport) {
                                div {
                                    css { marginTop = 6.px; display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px }
                                    textarea {
                                        css { width = 100.pct; height = 65.px; background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#38bdf8"); fontFamily = FontFamily.monospace; fontSize = 12.px; padding = 6.px; resize = "none".unsafeCast<Resize>() }
                                        placeholder = "Paste JWK JSON or COSE Key CBOR Hex for this doc..."
                                        value = docReq.dedicatedInputText
                                        onChange = { ev -> docReq.dedicatedInputText = ev.target.value }
                                    }
                                    button {
                                        css { background = Color("#7c3aed"); color = Color("#ffffff"); border = None.none; padding = Padding(4.px, 12.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold; width = 150.px }
                                        onClick = { loadCustomDedicatedReaderKey(docReq, docReq.dedicatedInputText) }
                                        +"📥 Set Dedicated Key"
                                    }
                                    if (docReq.dedicatedInputError.isNotEmpty()) {
                                        div { css { color = Color("#fca5a5"); fontSize = 11.px; fontWeight = FontWeight.bold }; +docReq.dedicatedInputError }
                                    }
                                }
                            }
                        }
                    }

                    // Namespaces editor
                    div {
                        css { marginBottom = 12.px }
                        h4 { css { fontSize = 14.px; color = Color("#cbd5e1"); margin = Margin(0.px, 0.px, 8.px, 0.px) }; +"Namespaces & Elements:" }

                        docReq.namespaces.forEachIndexed { nsIdx, ns ->
                            div {
                                css {
                                    background = Color("#0f172a")
                                    borderRadius = 6.px
                                    padding = 12.px
                                    marginBottom = 8.px
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                }

                                div {
                                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 8.px }
                                    div {
                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px }
                                        span { css { fontSize = 12.px; color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Namespace:" }
                                        input {
                                            css {
                                                background = Color("#1e293b")
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                borderRadius = 4.px
                                                color = Color("#4ade80")
                                                fontFamily = FontFamily.monospace
                                                fontSize = 13.px
                                                padding = Padding(2.px, 6.px)
                                                width = 220.px
                                            }
                                            value = ns.name
                                            onChange = { ev ->
                                                ns.name = ev.target.value
                                                rebuildRequest()
                                            }
                                        }
                                    }

                                    button {
                                        css {
                                            background = Color("#334155")
                                            color = Color("#94a3b8")
                                            border = None.none
                                            padding = Padding(2.px, 8.px)
                                            borderRadius = 4.px
                                            cursor = Cursor.pointer
                                            fontSize = 11.px
                                            hover { background = Color("#475569"); color = Color("#f1f5f9") }
                                        }
                                        onClick = {
                                            docReq.namespaces.removeAt(nsIdx)
                                            rebuildRequest()
                                        }
                                        +"Remove NS"
                                    }
                                }

                                // Elements list
                                div {
                                    css { display = Display.flex; flexWrap = FlexWrap.wrap; gap = 8.px; marginBottom = 8.px }
                                    ns.elements.forEachIndexed { elemIdx, elem ->
                                        div {
                                            css {
                                                background = Color("#1e293b")
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                borderRadius = 4.px
                                                padding = Padding(4.px, 8.px)
                                                display = Display.flex
                                                alignItems = AlignItems.center
                                                gap = 6.px
                                                fontSize = 12.px
                                            }
                                            span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +elem.name }
                                            label {
                                                css { display = Display.flex; alignItems = AlignItems.center; gap = 2.px; color = Color("#94a3b8"); fontSize = 11.px }
                                                input {
                                                    type = "checkbox".unsafeCast<InputType>()
                                                    checked = elem.intentToRetain
                                                    onChange = { ev ->
                                                        elem.intentToRetain = ev.target.checked
                                                        rebuildRequest()
                                                    }
                                                }
                                                +"Retain"
                                            }
                                            button {
                                                css {
                                                    background = Color("transparent")
                                                    color = Color("#ef4444")
                                                    border = None.none
                                                    cursor = Cursor.pointer
                                                    padding = 0.px
                                                    fontWeight = FontWeight.bold
                                                }
                                                onClick = {
                                                    ns.elements.removeAt(elemIdx)
                                                    rebuildRequest()
                                                }
                                                +"×"
                                            }
                                        }
                                    }
                                }

                                // Add Data Element inline
                                val nsKey = "${docReq.id}-$nsIdx"
                                val newElemName = newElemInputs[nsKey] ?: ""
                                div {
                                    css { display = Display.flex; gap = 6.px; alignItems = AlignItems.center }
                                    input {
                                        css {
                                            background = Color("#1e293b")
                                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                                            borderRadius = 4.px
                                            color = Color("#f1f5f9")
                                            fontSize = 12.px
                                            padding = Padding(2.px, 6.px)
                                            width = 140.px
                                        }
                                        placeholder = "New element name..."
                                        value = newElemName
                                        onChange = { ev -> newElemInputs = newElemInputs + (nsKey to ev.target.value) }
                                    }
                                    button {
                                        css {
                                            background = Color("#334155")
                                            color = Color("#38bdf8")
                                            border = None.none
                                            borderRadius = 4.px
                                            padding = Padding(2.px, 8.px)
                                            fontSize = 12.px
                                            fontWeight = FontWeight.bold
                                            cursor = Cursor.pointer
                                            hover { background = Color("#475569") }
                                        }
                                        onClick = {
                                            if (newElemName.isNotBlank()) {
                                                ns.elements.add(DataElementUiModel(newElemName.trim(), false))
                                                newElemInputs = newElemInputs - nsKey
                                                rebuildRequest()
                                            }
                                        }
                                        +"+ Add Element"
                                    }
                                }
                            }
                        }

                        // Add Namespace button
                        button {
                            css {
                                background = Color("#334155")
                                color = Color("#38bdf8")
                                border = None.none
                                borderRadius = 4.px
                                padding = Padding(4.px, 10.px)
                                fontSize = 12.px
                                fontWeight = FontWeight.bold
                                cursor = Cursor.pointer
                                hover { background = Color("#475569") }
                            }
                            onClick = {
                                docReq.namespaces.add(NamespaceUiModel("org.iso.18013.5.1", mutableListOf()))
                                rebuildRequest()
                            }
                            +"+ Add Namespace"
                        }
                    }

                    // DocRequestInfo Advanced Options Toggle
                    div {
                        css { marginTop = 8.px }
                        label {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#38bdf8"); fontSize = 13.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                            input {
                                type = "checkbox".unsafeCast<InputType>()
                                checked = docReq.docRequestInfoEnabled
                                onChange = { ev ->
                                    docReq.docRequestInfoEnabled = ev.target.checked
                                    rebuildRequest()
                                }
                            }
                            +"⚙️ Enable DocRequestInfo (DocFormat, ZKP, Mappings, Issuer IDs, Alt Elements)"
                        }

                        if (docReq.docRequestInfoEnabled) {
                            div {
                                css {
                                    background = Color("#0f172a")
                                    borderRadius = 6.px
                                    padding = 14.px
                                    marginTop = 8.px
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    gap = 12.px
                                    fontSize = 12.px
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                }

                                div {
                                    css { display = Display.flex; gap = 16.px; alignItems = AlignItems.center; flexWrap = FlexWrap.wrap }

                                    div {
                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px }
                                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"docFormat:" }
                                        input {
                                            css {
                                                background = Color("#1e293b")
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                borderRadius = 4.px
                                                color = Color("#f1f5f9")
                                                padding = Padding(2.px, 6.px)
                                                width = 130.px
                                            }
                                            placeholder = "e.g. mso_mdoc"
                                            value = docReq.docFormat
                                            onChange = { ev -> docReq.docFormat = ev.target.value; rebuildRequest() }
                                        }
                                    }

                                    div {
                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px }
                                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"uniqueDocSetRequired:" }
                                        select {
                                            css {
                                                background = Color("#1e293b")
                                                color = Color("#f1f5f9")
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                borderRadius = 4.px
                                                padding = Padding(2.px, 6.px)
                                            }
                                            value = docReq.uniqueDocSetRequired
                                            onChange = { ev -> docReq.uniqueDocSetRequired = ev.target.value; rebuildRequest() }
                                            option { value = "default"; +"Default (Unspecified)" }
                                            option { value = "true"; +"true" }
                                            option { value = "false"; +"false" }
                                        }
                                    }

                                    div {
                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px }
                                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"maxResponseSize (bytes):" }
                                        input {
                                            css {
                                                background = Color("#1e293b")
                                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                                borderRadius = 4.px
                                                color = Color("#f1f5f9")
                                                padding = Padding(2.px, 6.px)
                                                width = 110.px
                                            }
                                            placeholder = "e.g. 65536"
                                            value = docReq.maximumResponseSize
                                            onChange = { ev -> docReq.maximumResponseSize = ev.target.value; rebuildRequest() }
                                        }
                                    }
                                }

                                // Issuer Identifiers
                                div {
                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px }
                                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Issuer Identifiers (hex, comma separated):" }
                                    input {
                                        css {
                                            background = Color("#1e293b")
                                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                                            borderRadius = 4.px
                                            color = Color("#38bdf8")
                                            fontFamily = FontFamily.monospace
                                            padding = Padding(2.px, 6.px)
                                            width = 300.px
                                        }
                                        placeholder = "e.g. A1B2C3..., D4E5F6..."
                                        value = docReq.issuerIdentifiersHex
                                        onChange = { ev -> docReq.issuerIdentifiersHex = ev.target.value; rebuildRequest() }
                                    }
                                }

                                // ZK Request options
                                div {
                                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 8.px }
                                    div {
                                        css { display = Display.flex; gap = 16.px; alignItems = AlignItems.center }
                                        label {
                                            css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; color = Color("#cbd5e1") }
                                            input {
                                                type = "checkbox".unsafeCast<InputType>()
                                                checked = docReq.zkRequestEnabled
                                                onChange = { ev ->
                                                    docReq.zkRequestEnabled = ev.target.checked
                                                    if (ev.target.checked && docReq.selectedZkCircuitIds.isEmpty()) {
                                                        longfellowCircuits.forEach { spec -> docReq.selectedZkCircuitIds.add(spec.id) }
                                                    }
                                                    rebuildRequest()
                                                }
                                            }
                                            +"Request Zero-Knowledge Proof (zkRequest)"
                                        }
                                        if (docReq.zkRequestEnabled) {
                                            label {
                                                css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; color = Color("#cbd5e1") }
                                                input {
                                                    type = "checkbox".unsafeCast<InputType>()
                                                    checked = docReq.zkRequired
                                                    onChange = { ev -> docReq.zkRequired = ev.target.checked; rebuildRequest() }
                                                }
                                                +"Mark ZK Proof as mandatory (zkRequired)"
                                            }
                                        }
                                    }
                                    if (docReq.zkRequestEnabled) {
                                        div {
                                            css {
                                                padding = 10.px
                                                background = Color("#0f172a")
                                                borderRadius = 6.px
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            div {
                                                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 8.px }
                                                span { css { fontSize = 12.px; fontWeight = FontWeight.bold; color = Color("#a78bfa") }; +"Google Longfellow ZK Circuits to include:" }
                                                div {
                                                    css { display = Display.flex; gap = 8.px }
                                                    button {
                                                        css { background = Color("#334155"); color = Color("#f1f5f9"); border = None.none; padding = Padding(2.px, 6.px); borderRadius = 4.px; fontSize = 11.px; cursor = Cursor.pointer }
                                                        onClick = {
                                                            docReq.selectedZkCircuitIds.clear()
                                                            longfellowCircuits.forEach { spec -> docReq.selectedZkCircuitIds.add(spec.id) }
                                                            rebuildRequest()
                                                        }
                                                        +"Select All"
                                                    }
                                                    button {
                                                        css { background = Color("#334155"); color = Color("#f1f5f9"); border = None.none; padding = Padding(2.px, 6.px); borderRadius = 4.px; fontSize = 11.px; cursor = Cursor.pointer }
                                                        onClick = {
                                                            docReq.selectedZkCircuitIds.clear()
                                                            rebuildRequest()
                                                        }
                                                        +"Deselect All"
                                                    }
                                                }
                                            }
                                            div {
                                                css { display = Display.flex; flexWrap = FlexWrap.wrap; gap = 12.px }
                                                for (spec in longfellowCircuits) {
                                                    val isChecked = spec.id in docReq.selectedZkCircuitIds
                                                    val version = spec.getParam<Long>("version") ?: 0L
                                                    val numAttrs = spec.getParam<Long>("num_attributes") ?: 0L
                                                    val hash = spec.getParam<String>("circuit_hash") ?: ""
                                                    val hashTrunc = if (hash.length > 8) hash.take(8) + "..." else hash

                                                    label {
                                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; fontSize = 12.px; color = Color("#cbd5e1"); cursor = Cursor.pointer }
                                                        input {
                                                            type = "checkbox".unsafeCast<InputType>()
                                                            checked = isChecked
                                                            onChange = { ev ->
                                                                if (ev.target.checked) {
                                                                    docReq.selectedZkCircuitIds.add(spec.id)
                                                                } else {
                                                                    docReq.selectedZkCircuitIds.remove(spec.id)
                                                                }
                                                                rebuildRequest()
                                                            }
                                                        }
                                                        +"v$version ($numAttrs attr, $hashTrunc)"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Data Element Identifier Mappings Editor
                                div {
                                    css { marginTop = 4.px }
                                    h4 { css { fontSize = 12.px; color = Color("#a78bfa"); margin = Margin(0.px, 0.px, 6.px, 0.px) }; +"Data Element Identifier Mappings (JSONPath arrays):" }

                                    docReq.dataElementIdentifierMapping.forEachIndexed { mapIdx, m ->
                                        div {
                                            css { display = Display.flex; gap = 8.px; alignItems = AlignItems.center; marginBottom = 6.px }
                                            input {
                                                css { background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#f1f5f9"); padding = Padding(2.px, 6.px); width = 130.px }
                                                placeholder = "Data Element"
                                                value = m.dataElementName
                                                onChange = { ev -> m.dataElementName = ev.target.value; rebuildRequest() }
                                            }
                                            input {
                                                css { background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = Padding(2.px, 6.px); width = 280.px }
                                                placeholder = "[\"$\", \"credentialSubject\", \"familyName\"]"
                                                value = m.jsonPathStr
                                                onChange = { ev -> m.jsonPathStr = ev.target.value; rebuildRequest() }
                                            }
                                            button {
                                                css { background = Color("transparent"); color = Color("#ef4444"); border = None.none; cursor = Cursor.pointer; fontWeight = FontWeight.bold }
                                                onClick = { docReq.dataElementIdentifierMapping.removeAt(mapIdx); rebuildRequest() }
                                                +"×"
                                            }
                                        }
                                    }

                                    button {
                                        css { background = Color("#334155"); color = Color("#a78bfa"); border = None.none; borderRadius = 4.px; padding = Padding(2.px, 8.px); fontSize = 11.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                                        onClick = {
                                            docReq.dataElementIdentifierMapping.add(MappingUiModel("family_name", "[\"$\", \"credentialSubject\", \"familyName\"]"))
                                            rebuildRequest()
                                        }
                                        +"+ Add Data Element Mapping"
                                    }
                                }

                                // Alternative Data Elements Editor (Hierarchical: RequestedElement -> List<AlternativeSets>)
                                div {
                                    css { marginTop = 8.px }
                                    h4 { css { fontSize = 12.px; color = Color("#4ade80"); margin = Margin(0.px, 0.px, 8.px, 0.px) }; +"Alternative Data Element Rules (ISO 18013-5 / 18013-7):" }

                                    docReq.alternativeDataElements.forEachIndexed { altIdx, altSetUi ->
                                        div {
                                            css {
                                                background = Color("#1e293b")
                                                borderRadius = 6.px
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                padding = 12.px
                                                marginBottom = 10.px
                                            }

                                            // Requested Element Header
                                            div {
                                                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 8.px }
                                                div {
                                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; fontSize = 12.px }
                                                    span { css { color = Color("#f87171"); fontWeight = FontWeight.bold }; +"Requested Element:" }
                                                    input {
                                                        css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#f1f5f9"); padding = Padding(2.px, 6.px); width = 160.px; fontSize = 12.px }
                                                        placeholder = "Namespace"
                                                        value = altSetUi.requestedElement.namespace
                                                        onChange = { ev -> altSetUi.requestedElement.namespace = ev.target.value; rebuildRequest() }
                                                    }
                                                    input {
                                                        css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#38bdf8"); fontWeight = FontWeight.bold; padding = Padding(2.px, 6.px); width = 130.px; fontSize = 12.px }
                                                        placeholder = "Element Name"
                                                        value = altSetUi.requestedElement.elementIdentifier
                                                        onChange = { ev -> altSetUi.requestedElement.elementIdentifier = ev.target.value; rebuildRequest() }
                                                    }
                                                }

                                                button {
                                                    css { background = Color("#991b1b"); color = Color("#fca5a5"); border = None.none; padding = Padding(2.px, 8.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 11.px; hover { background = Color("#b91c1c"); color = Color("#ffffff") } }
                                                    onClick = { docReq.alternativeDataElements.removeAt(altIdx); rebuildRequest() }
                                                    +"🗑️ Remove Rule"
                                                }
                                            }

                                            // Alternative Sets
                                            div {
                                                css { paddingLeft = 16.px; borderLeft = Border(2.px, LineStyle.solid, Color("#4ade80")) }
                                                span { css { fontSize = 11.px; color = Color("#94a3b8"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 6.px }; +"Alternative Sets (Any of these sets can fulfill the request):" }

                                                altSetUi.alternativeSets.forEachIndexed { setIdx, setUi ->
                                                    div {
                                                        css {
                                                            background = Color("#0f172a")
                                                            padding = 8.px
                                                            borderRadius = 4.px
                                                            marginBottom = 6.px
                                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                        }

                                                        div {
                                                            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 6.px }
                                                            span { css { fontSize = 11.px; color = Color("#4ade80"); fontWeight = FontWeight.bold }; +"Option Set #${setIdx + 1}:" }
                                                            button {
                                                                css { background = Color("#334155"); color = Color("#94a3b8"); border = None.none; padding = Padding(1.px, 6.px); borderRadius = 4.px; cursor = Cursor.pointer; fontSize = 10.px; hover { background = Color("#475569"); color = Color("#ffffff") } }
                                                                onClick = { altSetUi.alternativeSets.removeAt(setIdx); rebuildRequest() }
                                                                +"Remove Set"
                                                            }
                                                        }

                                                        // Elements in Set
                                                        div {
                                                            css { display = Display.flex; flexWrap = FlexWrap.wrap; gap = 6.px; marginBottom = 6.px }
                                                            setUi.elements.forEachIndexed { elemIdx, elemUi ->
                                                                div {
                                                                    css { display = Display.flex; gap = 4.px; alignItems = AlignItems.center; background = Color("#1e293b"); padding = Padding(2.px, 6.px); borderRadius = 4.px; border = Border(1.px, LineStyle.solid, Color("#475569")) }
                                                                    input {
                                                                        css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#f1f5f9"); fontSize = 11.px; padding = Padding(1.px, 4.px); width = 120.px }
                                                                        placeholder = "Namespace"
                                                                        value = elemUi.namespace
                                                                        onChange = { ev -> elemUi.namespace = ev.target.value; rebuildRequest() }
                                                                    }
                                                                    input {
                                                                        css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#4ade80"); fontSize = 11.px; fontWeight = FontWeight.bold; padding = Padding(1.px, 4.px); width = 100.px }
                                                                        placeholder = "Element Name"
                                                                        value = elemUi.elementIdentifier
                                                                        onChange = { ev -> elemUi.elementIdentifier = ev.target.value; rebuildRequest() }
                                                                    }
                                                                    button {
                                                                        css { background = Color("transparent"); color = Color("#ef4444"); border = None.none; cursor = Cursor.pointer; fontWeight = FontWeight.bold; padding = 0.px }
                                                                        onClick = { setUi.elements.removeAt(elemIdx); rebuildRequest() }
                                                                        +"×"
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        button {
                                                            css { background = Color("#334155"); color = Color("#38bdf8"); border = None.none; borderRadius = 4.px; padding = Padding(2.px, 6.px); fontSize = 10.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                                                            onClick = {
                                                                setUi.elements.add(ElementReferenceUiModel(namespace = "org.iso.18013.5.1", elementIdentifier = ""))
                                                                rebuildRequest()
                                                            }
                                                            +"+ Add Element to Set"
                                                        }
                                                    }
                                                }

                                                button {
                                                    css { background = Color("#334155"); color = Color("#4ade80"); border = None.none; borderRadius = 4.px; padding = Padding(3.px, 8.px); fontSize = 11.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer; marginTop = 4.px }
                                                    onClick = {
                                                        val newSet = AlternativeSetUiModel(mutableListOf(ElementReferenceUiModel(namespace = "org.iso.18013.5.1", elementIdentifier = "given_name_national")))
                                                        altSetUi.alternativeSets.add(newSet)
                                                        rebuildRequest()
                                                    }
                                                    +"+ Add Alternative Set Option"
                                                }
                                            }
                                        }
                                    }

                                    button {
                                        css { background = Color("#334155"); color = Color("#4ade80"); border = None.none; borderRadius = 4.px; padding = Padding(4.px, 10.px); fontSize = 12.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                                        onClick = {
                                            val newAltRule = AlternativeDataElementSetUiModel(
                                                requestedElement = ElementReferenceUiModel(namespace = "org.iso.18013.5.1", elementIdentifier = "given_name"),
                                                alternativeSets = mutableListOf(
                                                    AlternativeSetUiModel(mutableListOf(ElementReferenceUiModel(namespace = "org.iso.18013.5.1", elementIdentifier = "given_name_national")))
                                                )
                                            )
                                            docReq.alternativeDataElements.add(newAltRule)
                                            rebuildRequest()
                                        }
                                        +"+ Add Alternative Data Element Rule"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: DeviceRequestInfo (ISO 18013-5 2nd Ed / 18013-7 UseCases & Purpose Hints)
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            div {
                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }

                label {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#38bdf8"); fontSize = 1.2.rem; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = deviceRequestInfoEnabled
                        onChange = { ev ->
                            deviceRequestInfoEnabled = ev.target.checked
                            rebuildRequest()
                        }
                    }
                    +"⚙️ Enable DeviceRequestInfo (UseCases & Purpose Hints)"
                }

                if (deviceRequestInfoEnabled) {
                    button {
                        css {
                            background = Color("#2563eb")
                            color = Color("#ffffff")
                            border = None.none
                            padding = Padding(6.px, 14.px)
                            borderRadius = 6.px
                            fontWeight = FontWeight.bold
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#1d4ed8") }
                        }
                        onClick = {
                            val newUc = UseCaseUiModel(
                                id = "uc-${Random.nextInt(10000, 99999)}",
                                mandatory = true,
                                documentSets = mutableListOf(DocumentSetUiModel(mutableListOf(0))),
                                purposeHints = mutableListOf()
                            )
                            useCases.add(newUc)
                            rebuildRequest()
                        }
                        +"+ Add Use-Case"
                    }
                }
            }

            if (deviceRequestInfoEnabled) {
                div {
                    css { marginTop = 12.px; fontSize = 13.px }
                    p { css { color = Color("#94a3b8"); margin = Margin(0.px, 0.px, 12.px, 0.px) }; +"ISO 18013-5 2nd Edition UseCases specify acceptable document sets (credential combinations) and purpose hints." }

                    if (useCases.isEmpty()) {
                        div {
                            css { color = Color("#64748b"); fontStyle = FontStyle.italic; padding = 12.px }
                            +"No use-cases defined. Click '+ Add Use-Case' to add acceptable document sets and purpose hints."
                        }
                    }

                    useCases.forEachIndexed { ucIdx, uc ->
                        div {
                            css {
                                background = Color("#1e293b")
                                borderRadius = 8.px
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                padding = 16.px
                                marginBottom = 14.px
                            }

                            // UseCase Header Row
                            div {
                                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }

                                div {
                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px }
                                    span { css { fontWeight = FontWeight.bold; color = Color("#a78bfa"); fontSize = 14.px }; +"UseCase #${ucIdx + 1}" }
                                    label {
                                        css { display = Display.flex; alignItems = AlignItems.center; gap = 4.px; color = Color("#cbd5e1"); fontWeight = FontWeight.bold; cursor = Cursor.pointer; fontSize = 12.px }
                                        input {
                                            type = "checkbox".unsafeCast<InputType>()
                                            checked = uc.mandatory
                                            onChange = { ev ->
                                                uc.mandatory = ev.target.checked
                                                rebuildRequest()
                                            }
                                        }
                                        +"Mandatory Use-Case"
                                    }
                                }

                                button {
                                    css { background = Color("#991b1b"); color = Color("#fca5a5"); border = None.none; padding = Padding(4.px, 10.px); borderRadius = 6.px; cursor = Cursor.pointer; fontSize = 12.px; hover { background = Color("#b91c1c"); color = Color("#ffffff") } }
                                    onClick = {
                                        useCases.removeAt(ucIdx)
                                        rebuildRequest()
                                    }
                                    +"🗑️ Remove Use-Case"
                                }
                            }

                            // Document Sets Section
                            div {
                                css { marginBottom = 12.px; padding = 12.px; background = Color("#0f172a"); borderRadius = 6.px; border = Border(1.px, LineStyle.solid, Color("#334155")) }
                                div {
                                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 8.px }
                                    h4 { css { fontSize = 13.px; color = Color("#38bdf8"); margin = 0.px }; +"Document Sets (Acceptable document combinations):" }

                                    button {
                                        css { background = Color("#334155"); color = Color("#38bdf8"); border = None.none; borderRadius = 4.px; padding = Padding(3.px, 8.px); fontSize = 11.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer; hover { background = Color("#475569") } }
                                        onClick = {
                                            uc.documentSets.add(DocumentSetUiModel(mutableListOf(0)))
                                            rebuildRequest()
                                        }
                                        +"+ Add Document Set"
                                    }
                                }

                                uc.documentSets.forEachIndexed { dsIdx, ds ->
                                    div {
                                        css { background = Color("#1e293b"); padding = 10.px; borderRadius = 6.px; marginBottom = 8.px; border = Border(1.px, LineStyle.solid, Color("#475569")) }
                                        div {
                                            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 6.px }
                                            span { css { fontSize = 12.px; color = Color("#4ade80"); fontWeight = FontWeight.bold }; +"Document Set #${dsIdx + 1}:" }
                                            if (uc.documentSets.size > 1) {
                                                button {
                                                    css { background = Color("transparent"); color = Color("#ef4444"); border = None.none; cursor = Cursor.pointer; fontSize = 11.px; fontWeight = FontWeight.bold }
                                                    onClick = {
                                                        uc.documentSets.removeAt(dsIdx)
                                                        rebuildRequest()
                                                    }
                                                    +"Remove Set"
                                                }
                                            }
                                        }

                                        // Badges of docRequestIds in set
                                        div {
                                            css { display = Display.flex; flexWrap = FlexWrap.wrap; gap = 6.px; alignItems = AlignItems.center; marginBottom = 6.px }
                                            ds.docRequestIds.forEachIndexed { idIdx, docId ->
                                                val docName = docRequests.getOrNull(docId)?.docType ?: "Doc #$docId"
                                                div {
                                                    css { background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#38bdf8")); borderRadius = 4.px; padding = Padding(2.px, 8.px); display = Display.flex; alignItems = AlignItems.center; gap = 6.px; fontSize = 12.px }
                                                    span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +"DocReq ID $docId: $docName" }
                                                    if (ds.docRequestIds.size > 1) {
                                                        button {
                                                            css { background = Color("transparent"); color = Color("#ef4444"); border = None.none; cursor = Cursor.pointer; fontWeight = FontWeight.bold; padding = 0.px }
                                                            onClick = {
                                                                ds.docRequestIds.removeAt(idIdx)
                                                                rebuildRequest()
                                                            }
                                                            +"×"
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Add docRequestId selector
                                        val dsKey = "${uc.id}-$dsIdx"
                                        val selectedDocId = selectedDocReqInputs[dsKey] ?: 0
                                        div {
                                            css { display = Display.flex; gap = 6.px; alignItems = AlignItems.center }
                                            select {
                                                css { background = Color("#0f172a"); color = Color("#f1f5f9"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 4.px; padding = Padding(2.px, 6.px); fontSize = 12.px }
                                                value = selectedDocId.toString()
                                                onChange = { ev ->
                                                    selectedDocReqInputs = selectedDocReqInputs + (dsKey to (ev.target.value.toIntOrNull() ?: 0))
                                                }
                                                docRequests.forEachIndexed { dIdx, dObj ->
                                                    option {
                                                        value = dIdx.toString()
                                                        +"[ID $dIdx] ${dObj.docType}"
                                                    }
                                                }
                                                if (docRequests.isEmpty()) {
                                                    option { value = "0"; +"[ID 0] Default Document Request" }
                                                }
                                            }

                                            button {
                                                css { background = Color("#334155"); color = Color("#38bdf8"); border = None.none; borderRadius = 4.px; padding = Padding(2.px, 8.px); fontSize = 11.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                                                onClick = {
                                                    if (!ds.docRequestIds.contains(selectedDocId)) {
                                                        ds.docRequestIds.add(selectedDocId)
                                                        rebuildRequest()
                                                    }
                                                }
                                                +"+ Add docRequestId to Set"
                                            }
                                        }
                                    }
                                }
                            }

                            // Purpose Hints Section
                            div {
                                css { padding = 12.px; background = Color("#0f172a"); borderRadius = 6.px; border = Border(1.px, LineStyle.solid, Color("#334155")) }
                                div {
                                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 8.px }
                                    h4 { css { fontSize = 13.px; color = Color("#a78bfa"); margin = 0.px }; +"Purpose Hints (Map of PurposeControllerId -> Hint Code):" }

                                    button {
                                        css { background = Color("#334155"); color = Color("#a78bfa"); border = None.none; borderRadius = 4.px; padding = Padding(3.px, 8.px); fontSize = 11.px; fontWeight = FontWeight.bold; cursor = Cursor.pointer; hover { background = Color("#475569") } }
                                        onClick = {
                                            uc.purposeHints.add(PurposeHintUiModel("org.iso.jtc1.sc17", 1))
                                            rebuildRequest()
                                        }
                                        +"+ Add Purpose Hint"
                                    }
                                }

                                if (uc.purposeHints.isEmpty()) {
                                    div { css { color = Color("#64748b"); fontStyle = FontStyle.italic; fontSize = 11.px }; +"No purpose hints added for this Use-Case." }
                                }

                                uc.purposeHints.forEachIndexed { hintIdx, hint ->
                                    div {
                                        css { display = Display.flex; gap = 8.px; alignItems = AlignItems.center; marginBottom = 6.px }
                                        input {
                                            css { background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#f1f5f9"); padding = Padding(2.px, 6.px); width = 200.px; fontSize = 12.px }
                                            placeholder = "PurposeControllerId"
                                            value = hint.namespace
                                            onChange = { ev ->
                                                hint.namespace = ev.target.value
                                                rebuildRequest()
                                            }
                                        }
                                        input {
                                            css { background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = Padding(2.px, 6.px); width = 100.px; fontSize = 12.px }
                                            placeholder = "Hint Code"
                                            value = hint.code.toString()
                                            onChange = { ev ->
                                                hint.code = ev.target.value.toIntOrNull() ?: 1
                                                rebuildRequest()
                                            }
                                        }
                                        button {
                                            css { background = Color("transparent"); color = Color("#ef4444"); border = None.none; cursor = Cursor.pointer; fontWeight = FontWeight.bold }
                                            onClick = {
                                                uc.purposeHints.removeAt(hintIdx)
                                                rebuildRequest()
                                            }
                                            +"×"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Generated W3C Digital Credentials Request JSON & Hex
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 20.px
                marginBottom = 24.px
            }

            div {
                css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 12.px }
                h3 { css { fontSize = 1.2.rem; color = Color("#4ade80"); margin = 0.px }; +"📦 Generated W3C Digital Credentials API Request" }

                div {
                    css { display = Display.flex; gap = 8.px }
                    button {
                        css {
                            background = Color("#3b82f6")
                            color = Color("#ffffff")
                            border = None.none
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            fontWeight = FontWeight.bold
                            hover { background = Color("#2563eb") }
                        }
                        onClick = {
                            window.navigator.asDynamic().clipboard.writeText(generatedW3cJson)
                            copyStatus = "Copied JSON!"
                            window.setTimeout({ copyStatus = "" }, 2000)
                        }
                        +if (copyStatus.isNotEmpty()) copyStatus else "📋 Copy Request JSON"
                    }

                    button {
                        css {
                            background = Color("#334155")
                            color = Color("#f1f5f9")
                            border = None.none
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            hover { background = Color("#475569") }
                        }
                        onClick = {
                            window.navigator.asDynamic().clipboard.writeText(generatedDevReqHex)
                            copyStatus = "Copied DevReq Hex!"
                            window.setTimeout({ copyStatus = "" }, 2000)
                        }
                        +"📋 Copy DeviceRequest Hex"
                    }
                }
            }

            if (buildError.isNotEmpty()) {
                div {
                    css { color = Color("#fca5a5"); background = Color("#451a1a"); border = Border(1.px, LineStyle.solid, Color("#f87171")); padding = 12.px; borderRadius = 6.px; fontWeight = FontWeight.bold }
                    +buildError
                }
            } else {
                textarea {
                    css {
                        width = 100.pct
                        height = 140.px
                        background = Color("#1e293b")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 8.px
                        color = Color("#f1f5f9")
                        fontFamily = FontFamily.monospace
                        fontSize = 13.px
                        padding = 10.px
                        resize = "none".unsafeCast<Resize>()
                    }
                    readOnly = true
                    value = generatedW3cJson
                }
            }

            // Diagnostic views for CBOR
            if (generatedDevReqDataItem != null) {
                div {
                    css { marginTop = 12.px }
                    h4 { css { fontSize = 13.px; color = Color("#94a3b8"); margin = Margin(0.px, 0.px, 6.px, 0.px) }; +"DeviceRequest CBOR Diagnostic View:" }
                    CborDiagnosticViewer {
                        diagText = Cbor.toDiagnostics(generatedDevReqDataItem!!, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                        maxHeight = 220.px
                    }
                }
            }
        }

        // Section: Browser Invocation via `navigator.credentials.get()`
        div {
            css {
                background = Color("#0f172a")
                borderRadius = 12.px
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = 24.px
                textAlign = TextAlign.center
                marginBottom = 24.px
            }

            button {
                css {
                    padding = Padding(14.px, 32.px)
                    fontSize = 18.px
                    fontWeight = FontWeight.bold
                    background = Color("linear-gradient(to right, #2563eb, #7c3aed)")
                    color = Color("#ffffff")
                    border = None.none
                    borderRadius = 10.px
                    cursor = Cursor.pointer
                    boxShadow = BoxShadow(0.px, 4.px, 14.px, Color("rgba(37, 99, 235, 0.4)"))
                    transition = "all 0.2s".unsafeCast<Transition>()
                    hover {
                        transform = "translateY(-1px)".unsafeCast<Transform>()
                    }
                    disabled {
                        background = Color("#475569")
                        cursor = Cursor.notAllowed
                    }
                }
                disabled = generatedW3cJson.isBlank() || isInvoking
                onClick = {
                    mainScope.launch {
                        try {
                            isInvoking = true
                            invokeError = ""

                            val navCreds = window.navigator.asDynamic().credentials
                            if (navCreds == null || navCreds.get == null) {
                                error("Digital Credentials API (navigator.credentials.get) is not supported in this browser. In Chrome, enable chrome://flags#web-identity-digital-credentials.")
                            }

                            val reqJsObject = JSON.parse<dynamic>(generatedW3cJson)

                            val credResponsePromise = navCreds.get(reqJsObject).unsafeCast<kotlin.js.Promise<dynamic>>()
                            val credResponseJs = credResponsePromise.await()

                            val rawProtocol = credResponseJs.protocol
                            val protocolStr = if (rawProtocol != null && rawProtocol != undefined) {
                                rawProtocol.toString()
                            } else {
                                "org-iso-mdoc"
                            }

                            val rawData = credResponseJs.data
                            val dataElement = when {
                                rawData == null || rawData == undefined -> buildJsonObject {}
                                js("typeof rawData === 'string'").unsafeCast<Boolean>() -> {
                                    val s = rawData.unsafeCast<String>()
                                    try {
                                        Json.parseToJsonElement(s)
                                    } catch (e: Throwable) {
                                        buildJsonObject { put("response", s) }
                                    }
                                }
                                else -> {
                                    try {
                                        Json.parseToJsonElement(JSON.stringify(rawData))
                                    } catch (e: Throwable) {
                                        buildJsonObject {}
                                    }
                                }
                            }

                            val finalDataObj = when {
                                dataElement is JsonObject && dataElement.containsKey("response") -> dataElement
                                dataElement is JsonObject -> dataElement
                                else -> buildJsonObject { put("response", dataElement.jsonPrimitive.content) }
                            }

                            val respObj = buildJsonObject {
                                put("protocol", protocolStr)
                                put("data", finalDataObj)
                            }

                            // Auto-decrypt
                            decryptResponseObject(respObj)
                        } catch (e: Throwable) {
                            invokeError = "Invocation failed / cancelled: ${e.message ?: e.toString()}"
                        } finally {
                            isInvoking = false
                        }
                    }
                }
                +if (isInvoking) "⏳ Requesting Credentials from Browser..." else "🚀 Request Credentials via navigator.credentials.get()"
            }

            if (invokeError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 16.px
                        padding = 12.px
                        background = Color("#451a1a")
                        border = Border(1.px, LineStyle.solid, Color("#f87171"))
                        borderRadius = 8.px
                        color = Color("#fca5a5")
                        fontWeight = FontWeight.bold
                        textAlign = TextAlign.left
                    }
                    +invokeError
                }
            }
        }

        // Section: Decrypted Response View & Feed Actions
        val resp = decryptedResponse
        val devResp = decryptedDevReqObj
        if (resp != null && devResp != null) {
            div {
                css {
                    background = Color("#0f172a")
                    borderRadius = 12.px
                    border = Border(1.px, LineStyle.solid, Color("#22c55e"))
                    padding = 24.px
                    marginBottom = 24.px
                }

                div {
                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 16.px }

                    h3 { css { color = Color("#4ade80"); fontSize = 1.4.rem; margin = 0.px }; +"🎉 Response Decrypted Successfully!" }

                    div {
                        css { display = Display.flex; gap = 12.px }

                        button {
                            css {
                                background = Color("#3b82f6")
                                color = Color("#ffffff")
                                border = None.none
                                padding = Padding(8.px, 16.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontWeight = FontWeight.bold
                                fontSize = 13.px
                                hover { background = Color("#2563eb") }
                            }
                            onClick = {
                                window.navigator.asDynamic().clipboard.writeText(decryptedDevReqHex)
                                copyStatus = "Copied DevResp Hex!"
                                window.setTimeout({ copyStatus = "" }, 2000)
                            }
                            +if (copyStatus.isNotEmpty()) copyStatus else "📋 Copy Raw DeviceResponse Hex"
                        }


                    }
                }

                if (verificationError.isNotEmpty()) {
                    div {
                        css {
                            background = Color("#451a1a")
                            border = Border(1.px, LineStyle.solid, Color("#f87171"))
                            borderRadius = 8.px
                            color = Color("#fca5a5")
                            padding = 12.px
                            marginBottom = 16.px
                            fontSize = 13.px
                        }
                        +"⚠️ Verification warning: $verificationError"
                    }
                }

                div {
                    css { display = Display.flex; gap = 16.px; marginBottom = 16.px }

                    div {
                        css { background = Color("#1e293b"); padding = Padding(10.px, 16.px); borderRadius = 8.px }
                        span { css { color = Color("#94a3b8"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"STATUS" }
                        span { css { fontSize = 18.px; fontWeight = FontWeight.bold; color = if (devResp.status == 0) Color("#4ade80") else Color("#f87171") }; +devResp.status.toString() }
                    }

                    div {
                        css { background = Color("#1e293b"); padding = Padding(10.px, 16.px); borderRadius = 8.px }
                        span { css { color = Color("#94a3b8"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"DOCUMENTS PRESENTED" }
                        span { css { fontSize = 18.px; fontWeight = FontWeight.bold; color = Color("#38bdf8") }; +devResp.documents.size.toString() }
                    }
                }

                devResp.documents.forEachIndexed { idx, doc ->
                    div {
                        css {
                            background = Color("#1e293b")
                            borderRadius = 8.px
                            padding = 16.px
                            marginBottom = 12.px
                        }
                        div { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold }; +"Document #${idx + 1}: ${doc.docType}" }
                    }
                }

                CborDiagnosticViewer {
                    diagText = Cbor.toDiagnostics(resp.deviceResponse, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                    maxHeight = 300.px
                }
            }
        }
    }
}
