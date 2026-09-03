#include <map>
#include <set>
#include <algorithm>
#include <sstream>

#include "base64.h"
#include "cppbor_parse.h"
#include "x509_aki.h"

#include "Request.h"
#include "logger.h"

using namespace std;

// Helper to extract AKIs from a COSE_Sign1 structure
static void extractAkisFromCoseSign1(const cppbor::Item* coseSign1Item, std::vector<std::vector<uint8_t>>& outAkis) {
    if (!coseSign1Item) return;
    const cppbor::Array* arr = nullptr;
    if (coseSign1Item->asSemanticTag() && coseSign1Item->asSemanticTag()->asArray()) {
        arr = coseSign1Item->asSemanticTag()->asArray();
    } else if (coseSign1Item->asArray()) {
        arr = coseSign1Item->asArray();
    }
    if (!arr || arr->size() < 4) return;

    auto extractFromMap = [&](const cppbor::Map* map) {
        if (!map) return;
        for (auto it = map->begin(); it != map->end(); ++it) {
            bool isX5Chain = false;
            if (it->first->asUint() && it->first->asUint()->value() == 33) {
                isX5Chain = true;
            } else if (it->first->asInt() && it->first->asInt()->value() == 33) {
                isX5Chain = true;
            }
            if (isX5Chain) {
                if (it->second->asBstr()) {
                    const auto& bstrVal = it->second->asBstr()->value();
                    std::vector<uint8_t> aki;
                    if (x509::extractAkiFromDerCert(bstrVal.data(), bstrVal.size(), aki)) {
                        outAkis.push_back(aki);
                    }
                } else if (it->second->asArray()) {
                    auto chainArr = it->second->asArray();
                    for (size_t c = 0; c < chainArr->size(); ++c) {
                        const auto& certElem = chainArr->get(c);
                        if (certElem && certElem->asBstr()) {
                            const auto& bstrVal = certElem->asBstr()->value();
                            std::vector<uint8_t> aki;
                            if (x509::extractAkiFromDerCert(bstrVal.data(), bstrVal.size(), aki)) {
                                outAkis.push_back(aki);
                            }
                        }
                    }
                }
            }
        }
    };

    // 1. Check unprotected header (arr->get(1))
    if (arr->get(1) && arr->get(1)->asMap()) {
        extractFromMap(arr->get(1)->asMap());
    }

    // 2. Check protected header (arr->get(0)) which is a bstr containing CBOR map
    if (arr->get(0) && arr->get(0)->asBstr()) {
        const auto& protBytes = arr->get(0)->asBstr()->value();
        if (!protBytes.empty()) {
            auto [parsedProt, pos, msg] = cppbor::parse(protBytes.data(), protBytes.size());
            if (parsedProt && parsedProt->asMap()) {
                extractFromMap(parsedProt->asMap());
            }
        }
    }
}

// Helper struct for generating permutations
struct PermutationResult {
    std::vector<std::string> claimIds;
    int score;
};

// Recursive function to generate Cartesian product of options
void generatePermutations(
        size_t depth,
        const std::vector<std::string>& currentClaims,
        int currentScore,
        const std::vector<std::vector<std::vector<std::string>>>& logicalRequirements,
        std::vector<PermutationResult>& results
) {
    if (depth == logicalRequirements.size()) {
        results.push_back({currentClaims, currentScore});
        return;
    }

    const auto& fieldOptions = logicalRequirements[depth];
    for (size_t i = 0; i < fieldOptions.size(); ++i) {
        const auto& optionClaimIds = fieldOptions[i];
        int newScore = currentScore + (int)i;

        std::vector<std::string> newClaims = currentClaims;
        newClaims.insert(newClaims.end(), optionClaimIds.begin(), optionClaimIds.end());

        generatePermutations(depth + 1, newClaims, newScore, logicalRequirements, results);
    }
}

std::unique_ptr<MdocRequest> MdocRequest::parseMdocApi(const std::string& protocolName, cJSON* dataJson) {
    cJSON* deviceRequestJson = cJSON_GetObjectItem(dataJson, "deviceRequest");
    if (!deviceRequestJson || !cJSON_IsString(deviceRequestJson)) {
        LOG("Error: deviceRequest not found or not a string");
        return nullptr;
    }
    std::string deviceRequestBase64 = std::string(cJSON_GetStringValue(deviceRequestJson));

    std::string deviceRequestBytes = base64UrlDecode(deviceRequestBase64);
    auto [item, pos, message] = cppbor::parse(
            (const uint8_t*) deviceRequestBytes.data(), deviceRequestBytes.size());

    if (!item) {
        LOG("Error parsing DeviceRequest CBOR: %s", message.c_str());
        return nullptr;
    }

    auto map = item->asMap();
    if (!map) {
        LOG("Error: DeviceRequest is not a map");
        return nullptr;
    }

    std::string version = "1.0";
    const auto& versionItem = map->get("version");
    if (versionItem && versionItem->asTstr()) {
        version = versionItem->asTstr()->value();
    }
    int major = 1;
    int minor = 0;
    bool isVersion10 = true;
    if (sscanf(version.c_str(), "%d.%d", &major, &minor) >= 2) {
        isVersion10 = (major < 1 || (major == 1 && minor < 1));
    } else {
        isVersion10 = (version == "1.0");
    }

    // --- Parse DocRequests ---
    const auto& docRequestsArrayItem = map->get("docRequests");
    if (!docRequestsArrayItem || !docRequestsArrayItem->asArray()) {
        LOG("Error: docRequests missing or not an array");
        return nullptr;
    }
    auto docRequestsArray = docRequestsArrayItem->asArray();

    std::vector<std::vector<uint8_t>> topLevelReaderAkis;
    if (!isVersion10) {
        const auto& readerAuthAllItem = map->get("readerAuthAll");
        if (readerAuthAllItem && readerAuthAllItem->asArray()) {
            auto arr = readerAuthAllItem->asArray();
            for (size_t k = 0; k < arr->size(); ++k) {
                extractAkisFromCoseSign1(arr->get(k).get(), topLevelReaderAkis);
            }
        }
    }

    std::vector<DcqlCredentialQuery> credentialQueries;

    for (size_t i = 0; i < docRequestsArray->size(); ++i) {
        const auto& docRequestItem = docRequestsArray->get(i);
        if (!docRequestItem || !docRequestItem->asMap()) continue;
        auto docRequestMap = docRequestItem->asMap();

        std::vector<std::vector<uint8_t>> readerAuthAkis = topLevelReaderAkis;
        const auto& readerAuthItem = docRequestMap->get("readerAuth");
        if (readerAuthItem) {
            extractAkisFromCoseSign1(readerAuthItem.get(), readerAuthAkis);
        }

        const auto& itemsRequestItem = docRequestMap->get("itemsRequest");
        if (!itemsRequestItem) continue;

        // itemsRequest is usually Tagged(24, bstr)
        const uint8_t* irData = nullptr;
        size_t irSize = 0;

        if (itemsRequestItem->asSemanticTag()) {
            auto bstr = itemsRequestItem->asSemanticTag()->asBstr();
            if (bstr) {
                irData = bstr->value().data();
                irSize = bstr->value().size();
            }
        } else if (itemsRequestItem->asBstr()) {
            // Handle case where it might just be a bstr
            irData = itemsRequestItem->asBstr()->value().data();
            irSize = itemsRequestItem->asBstr()->value().size();
        }

        if (!irData) continue;

        auto [itemsRequestParsed, pos2, message2] = cppbor::parse(irData, irSize);
        if (!itemsRequestParsed) continue;

        auto itemsRequestMap = itemsRequestParsed->asMap();
        if (!itemsRequestMap) continue;

        const auto& docTypeItem = itemsRequestMap->get("docType");
        if (!docTypeItem || !docTypeItem->asTstr()) continue;
        std::string docType = docTypeItem->asTstr()->value();

        std::string credId = "cred" + std::to_string(i);

        // Registry to track unique claims: "namespace/element" -> "claimID"
        std::map<std::string, std::string> claimIdRegistry;
        std::vector<DcqlRequestedClaim> dcqlClaims;
        int claimCounter = 0;

        // --- Handle AlternativeDataElements (DocRequestInfo) ---
        std::vector<std::vector<std::vector<std::string>>> logicalRequirements;

        const auto& nameSpacesItem = itemsRequestMap->get("nameSpaces");
        if (!nameSpacesItem || !nameSpacesItem->asMap()) continue;
        auto nameSpacesMap = nameSpacesItem->asMap();

        // Check for requestInfo / alternativeDataElements
        cppbor::Array* altDataElementsArray = nullptr;
        std::string docFormat = "mso_mdoc";
        std::map<std::string, std::vector<std::string>> dataElementIdentifierMapping;
        std::vector<std::vector<uint8_t>> issuerIdentifiers;

        const auto& requestInfoItem = itemsRequestMap->get("requestInfo");
        if (requestInfoItem) {
            auto riMap = requestInfoItem->asMap();
            if (riMap) {
                const auto& altItem = riMap->get("alternativeDataElements");
                if (altItem) altDataElementsArray = altItem->asArray();

                const auto& docFormatItem = riMap->get("docFormat");
                if (docFormatItem && docFormatItem->asTstr()) {
                    docFormat = docFormatItem->asTstr()->value();
                }

                const auto& issuerIdentifiersItem = riMap->get("issuerIdentifiers");
                if (issuerIdentifiersItem && issuerIdentifiersItem->asArray()) {
                    auto arr = issuerIdentifiersItem->asArray();
                    for (size_t k = 0; k < arr->size(); ++k) {
                        const auto& elem = arr->get(k);
                        if (elem && elem->asBstr()) {
                            issuerIdentifiers.push_back(elem->asBstr()->value());
                        }
                    }
                }

                const auto& deimItem = riMap->get("dataElementIdentifierMapping");
                if (deimItem && deimItem->asMap()) {
                    auto deimMap = deimItem->asMap();
                    for (auto it = deimMap->begin(); it != deimMap->end(); ++it) {
                        std::string deName = it->first->asTstr()->value();
                        auto pathArray = it->second->asArray();
                        if (pathArray) {
                            std::vector<std::string> path;
                            for (size_t k = 0; k < pathArray->size(); ++k) {
                                const auto& pElem = pathArray->get(k);
                                if (pElem->asTstr()) {
                                    path.push_back(pElem->asTstr()->value());
                                } else if (pElem->asUint()) {
                                    path.push_back(std::to_string(pElem->asUint()->value()));
                                } else if (pElem->asInt()) {
                                    path.push_back(std::to_string(pElem->asInt()->value()));
                                }
                            }
                            dataElementIdentifierMapping[deName] = path;
                        }
                    }
                }
            }
        }

        auto registerClaim = [&](const std::string& ns, const std::string& elem, bool intent) -> std::string {
            std::string key = ns + "/" + elem;
            if (claimIdRegistry.find(key) != claimIdRegistry.end()) {
                return claimIdRegistry[key];
            }
            std::string id = "claim" + std::to_string(claimCounter++);
            std::vector<std::string> path;
            if (ns == "_") {
                if (dataElementIdentifierMapping.count(elem) > 0) {
                    path = dataElementIdentifierMapping[elem];
                } else {
                    LOG("Warning: No mapping for element %s in namespace _", elem.c_str());
                    path = {ns, elem};
                }
            } else {
                path = {ns, elem};
            }

            dcqlClaims.push_back(DcqlRequestedClaim{id, {}, path, intent});
            claimIdRegistry[key] = id;
            return id;
        };

        for (auto nsIt = nameSpacesMap->begin(); nsIt != nameSpacesMap->end(); ++nsIt) {
            std::string nsName = nsIt->first->asTstr()->value();
            auto elemsMap = nsIt->second->asMap();
            if (!elemsMap) continue;

            for (auto elemIt = elemsMap->begin(); elemIt != elemsMap->end(); ++elemIt) {
                std::string elemName = elemIt->first->asTstr()->value();
                bool intentToRetain = false;
                if (elemIt->second->asBool()) {
                    intentToRetain = elemIt->second->asBool()->value();
                }

                // Option 0: Base Claim
                std::string baseClaimId = registerClaim(nsName, elemName, intentToRetain);
                std::vector<std::vector<std::string>> optionsForThisField;
                optionsForThisField.push_back({baseClaimId});

                // Find Alternatives
                if (altDataElementsArray) {
                    for (size_t a = 0; a < altDataElementsArray->size(); ++a) {
                        const auto& altSetItem = altDataElementsArray->get(a);
                        if (!altSetItem || !altSetItem->asMap()) continue;
                        auto altSetMap = altSetItem->asMap();

                        const auto& reqElemItem = altSetMap->get("requestedElement");
                        if (!reqElemItem) continue;

                        std::string reqNs, reqElem;

                        // Support both Map ({"nameSpace": "...", "dataElement": "..."})
                        // and Array (["nameSpace", "dataElement"]) formats.
                        if (reqElemItem->asMap()) {
                            auto reqElemMap = reqElemItem->asMap();
                            const auto& reqNsItem = reqElemMap->get("nameSpace");
                            const auto& reqDeItem = reqElemMap->get("dataElement");
                            if (reqNsItem && reqNsItem->asTstr() && reqDeItem && reqDeItem->asTstr()) {
                                reqNs = reqNsItem->asTstr()->value();
                                reqElem = reqDeItem->asTstr()->value();
                            }
                        } else if (reqElemItem->asArray()) {
                            auto reqElemArray = reqElemItem->asArray();
                            if (reqElemArray->size() >= 2) {
                                const auto& reqNsItem = reqElemArray->get(0);
                                const auto& reqDeItem = reqElemArray->get(1);
                                if (reqNsItem && reqNsItem->asTstr() && reqDeItem && reqDeItem->asTstr()) {
                                    reqNs = reqNsItem->asTstr()->value();
                                    reqElem = reqDeItem->asTstr()->value();
                                }
                            }
                        }

                        if (!reqNs.empty() && reqNs == nsName && reqElem == elemName) {
                            const auto& altElemSetsItem = altSetMap->get("alternativeElementSets");
                            if (!altElemSetsItem || !altElemSetsItem->asArray()) continue;
                            auto altElemSets = altElemSetsItem->asArray();

                            for (size_t b = 0; b < altElemSets->size(); ++b) {
                                const auto& altSetArrayItem = altElemSets->get(b);
                                if (!altSetArrayItem || !altSetArrayItem->asArray()) continue;
                                auto altSet = altSetArrayItem->asArray();

                                std::vector<std::string> altOptionClaimIds;
                                bool setValid = true;
                                for (size_t c = 0; c < altSet->size(); ++c) {
                                    const auto& altRefItem = altSet->get(c);
                                    if (!altRefItem) { setValid = false; break; }

                                    std::string altNs, altDe;

                                    if (altRefItem->asMap()) {
                                        auto altRef = altRefItem->asMap();
                                        const auto& altNsItem = altRef->get("nameSpace");
                                        const auto& altDeItem = altRef->get("dataElement");
                                        if (altNsItem && altNsItem->asTstr() && altDeItem && altDeItem->asTstr()) {
                                            altNs = altNsItem->asTstr()->value();
                                            altDe = altDeItem->asTstr()->value();
                                        }
                                    } else if (altRefItem->asArray()) {
                                        auto altRefArray = altRefItem->asArray();
                                        if (altRefArray->size() >= 2) {
                                            const auto& altNsItem = altRefArray->get(0);
                                            const auto& altDeItem = altRefArray->get(1);
                                            if (altNsItem && altNsItem->asTstr() && altDeItem && altDeItem->asTstr()) {
                                                altNs = altNsItem->asTstr()->value();
                                                altDe = altDeItem->asTstr()->value();
                                            }
                                        }
                                    }

                                    if (altNs.empty() || altDe.empty()) {
                                        setValid = false;
                                        break;
                                    }

                                    // Register with original intent
                                    altOptionClaimIds.push_back(registerClaim(altNs, altDe, intentToRetain));
                                }
                                if (setValid) {
                                    optionsForThisField.push_back(altOptionClaimIds);
                                }
                            }
                        }
                    }
                }
                logicalRequirements.push_back(optionsForThisField);
            }
        }

        std::vector<DcqlClaimSet> claimSets;
        bool hasAlternatives = false;
        for (const auto& req : logicalRequirements) {
            if (req.size() > 1) { hasAlternatives = true; break; }
        }

        if (hasAlternatives) {
            std::vector<PermutationResult> permutations;
            generatePermutations(0, {}, 0, logicalRequirements, permutations);

            // Sort by score (ascending)
            std::sort(permutations.begin(), permutations.end(),
                      [](const PermutationResult& a, const PermutationResult& b) {
                          return a.score < b.score;
                      });

            for (const auto& perm : permutations) {
                claimSets.push_back(DcqlClaimSet{perm.claimIds});
            }
        }

        std::string format = "mso_mdoc";
        std::string mdocDocType = "";
        std::vector<std::string> vctValues;

        if (docFormat == "dc+sd-jwt") {
            format = "dc+sd-jwt";
            vctValues.push_back(docType);
        } else {
            mdocDocType = docType;
        }

        credentialQueries.push_back(DcqlCredentialQuery(
                credId,
                format,
                mdocDocType,
                vctValues,
                issuerIdentifiers,
                readerAuthAkis,
                dcqlClaims,
                claimSets,
                isVersion10
        ));
    }

    // --- Parse DeviceRequestInfo (UseCases) ---
    std::vector<DcqlCredentialSetQuery> credentialSetQueries;

    std::unique_ptr<cppbor::Item> drItem;
    cppbor::Map* drInfoMap = nullptr;

    if (!isVersion10) {
        const auto& deviceRequestInfoItem = map->get("deviceRequestInfo");
        if (deviceRequestInfoItem) {
            if (deviceRequestInfoItem->asSemanticTag()) {
                auto innerBytes = deviceRequestInfoItem->asSemanticTag()->asBstr();
                if (innerBytes) {
                    auto parseResult = cppbor::parse(innerBytes->value());
                    drItem = std::move(std::get<0>(parseResult));
                    if (drItem) drInfoMap = drItem->asMap();
                }
            }
        }
    }

    if (drInfoMap) {
        const auto& useCasesItem = drInfoMap->get("useCases");
        if (useCasesItem && useCasesItem->asArray()) {
            auto useCasesArray = useCasesItem->asArray();
            for (size_t u = 0; u < useCasesArray->size(); ++u) {
                const auto& useCaseItem = useCasesArray->get(u);
                if (!useCaseItem || !useCaseItem->asMap()) continue;
                auto useCaseMap = useCaseItem->asMap();

                bool mandatory = true;
                if (const auto& m = useCaseMap->get("mandatory")) {
                    if (m->asBool()) mandatory = m->asBool()->value();
                }

                const auto& documentSetsItem = useCaseMap->get("documentSets");
                if (!documentSetsItem || !documentSetsItem->asArray()) continue;
                auto documentSetsArray = documentSetsItem->asArray();

                std::vector<DcqlCredentialSetOption> options;

                for (size_t d = 0; d < documentSetsArray->size(); ++d) {
                    const auto& docSetItem = documentSetsArray->get(d);
                    if (!docSetItem) continue;

                    const cppbor::Array* docRequestIdsArray = nullptr;

                    // Support both Map (with "docRequestIds" key) and direct Array formats
                    if (docSetItem->asArray()) {
                        docRequestIdsArray = docSetItem->asArray();
                    } else if (docSetItem->asMap()) {
                        const auto& drIds = docSetItem->asMap()->get("docRequestIds");
                        if (drIds) docRequestIdsArray = drIds->asArray();
                    }

                    if (docRequestIdsArray) {
                        std::vector<std::string> credentialIds;
                        for (size_t r = 0; r < docRequestIdsArray->size(); ++r) {
                            const auto& idItem = docRequestIdsArray->get(r);
                            if (!idItem || !idItem->asUint()) continue;

                            uint64_t idx = idItem->asUint()->value();
                            if (idx < credentialQueries.size()) {
                                credentialIds.push_back(credentialQueries[idx].id);
                            }
                        }
                        // Only add if we found valid request IDs
                        if (!credentialIds.empty()) {
                            options.push_back(DcqlCredentialSetOption{credentialIds});
                        }
                    }
                }

                credentialSetQueries.push_back(DcqlCredentialSetQuery(mandatory, options));
            }
        }
    }

    DcqlQuery dcqlQuery(credentialQueries, credentialSetQueries);
    // dcqlQuery.log();

    return std::unique_ptr<MdocRequest> { new MdocRequest(protocolName, dcqlQuery) };
}

std::vector<Combination> MdocRequest::getCredentialCombinations(const CredentialDatabase* db, const std::string& protocol) {
    auto result = dcqlQuery.execute((CredentialDatabase*)db, protocol);
    if (result.has_value()) {
        return result.value().getCredentialCombinations();
    }
    return {};
}

std::unique_ptr<OpenID4VPRequest> OpenID4VPRequest::parseOpenID4VP(cJSON* dataJson, std::string protocolName) {
    std::string docTypeValue = "";
    auto dataElements = std::vector<MdocRequestDataElement>();
    std::vector<std::string> vctValues;
    auto vcClaims = std::vector<VcRequestedClaim>();
    auto dcqlCredentialQueries = std::vector<DcqlCredentialQuery>();
    auto dcqlCredentialSetQueries = std::vector<DcqlCredentialSetQuery>();

    std::vector<std::vector<uint8_t>> readerAuthAkis;

    cJSON* request = cJSON_GetObjectItem(dataJson, "request");
    if (request != nullptr) {
        std::string jwtStr = std::string(cJSON_GetStringValue(request));
        size_t firstDot = jwtStr.find(".");
        if (firstDot == std::string::npos) {
            return nullptr;
        }
        size_t secondDot = jwtStr.find(".", firstDot + 1);
        if (secondDot == std::string::npos) {
            return nullptr;
        }

        std::string headerBase64 = jwtStr.substr(0, firstDot);
        std::string headerJsonStr = base64UrlDecode(headerBase64);
        cJSON* headerJson = cJSON_Parse(headerJsonStr.c_str());
        if (headerJson) {
            cJSON* x5c = cJSON_GetObjectItem(headerJson, "x5c");
            if (x5c && cJSON_IsArray(x5c)) {
                cJSON* certObj;
                cJSON_ArrayForEach(certObj, x5c) {
                    if (cJSON_IsString(certObj)) {
                        std::string certDer = base64UrlDecode(cJSON_GetStringValue(certObj));
                        std::vector<uint8_t> aki;
                        if (x509::extractAkiFromDerCert((const uint8_t*)certDer.data(), certDer.size(), aki)) {
                            readerAuthAkis.push_back(aki);
                        }
                    }
                }
            }
            cJSON_Delete(headerJson);
        }

        std::string payloadBase64 = jwtStr.substr(firstDot + 1, secondDot - firstDot - 1);
        std::string payload = base64UrlDecode(payloadBase64);
        dataJson = cJSON_Parse(payload.c_str());
    } else {
        cJSON* signaturesItem = cJSON_GetObjectItem(dataJson, "signatures");
        if (signaturesItem && cJSON_IsArray(signaturesItem)) {
            cJSON* sig;
            cJSON_ArrayForEach(sig, signaturesItem) {
                cJSON* prot = cJSON_GetObjectItem(sig, "protected");
                if (prot && cJSON_IsString(prot)) {
                    std::string protJsonStr = base64UrlDecode(cJSON_GetStringValue(prot));
                    cJSON* protJson = cJSON_Parse(protJsonStr.c_str());
                    if (protJson) {
                        cJSON* x5c = cJSON_GetObjectItem(protJson, "x5c");
                        if (x5c && cJSON_IsArray(x5c)) {
                            cJSON* certObj;
                            cJSON_ArrayForEach(certObj, x5c) {
                                if (cJSON_IsString(certObj)) {
                                    std::string certDer = base64UrlDecode(cJSON_GetStringValue(certObj));
                                    std::vector<uint8_t> aki;
                                    if (x509::extractAkiFromDerCert((const uint8_t*)certDer.data(), certDer.size(), aki)) {
                                        readerAuthAkis.push_back(aki);
                                    }
                                }
                            }
                        }
                        cJSON_Delete(protJson);
                    }
                }
            }
        }

        cJSON* payloadItem = cJSON_GetObjectItem(dataJson, "payload");
        if (payloadItem != nullptr) {
            std::string payloadBase64 = std::string(cJSON_GetStringValue(payloadItem));
            std::string payload = base64UrlDecode(payloadBase64);
            dataJson = cJSON_Parse(payload.c_str());
        }
    }

    cJSON* query = cJSON_GetObjectItem(dataJson, "dcql_query");
    auto dcqlQuery = DcqlQuery::parse(query);
    if (!readerAuthAkis.empty()) {
        for (auto& cq : dcqlQuery.dcqlCredentialQueries) {
            cq.readerAuthAkis = readerAuthAkis;
        }
    }
    // dcqlQuery.log();

    return std::unique_ptr<OpenID4VPRequest> { new OpenID4VPRequest(
            protocolName,
            dcqlQuery
    )};
}