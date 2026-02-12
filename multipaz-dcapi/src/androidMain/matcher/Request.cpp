#include <map>
#include <set>
#include <algorithm>
#include <sstream>

#include "base64.h"
#include "cppbor_parse.h"

#include "Request.h"
#include "logger.h"

using namespace std;

std::string base64UrlDecode(const std::string& data) {
    size_t len = data.size();
    std::string s = data;
    if (data[len - 1] == '=') {
        // already have padding
    } else {
        size_t rem = len & 3;
        if (rem == 2) {
            s = s + "==";
        } else if (rem == 3) {
            s = s + "=";
        } else {
            // no padding needed
        }
    }
    return from_base64(s);
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

    // --- Parse DocRequests ---
    const auto& docRequestsArrayItem = map->get("docRequests");
    if (!docRequestsArrayItem || !docRequestsArrayItem->asArray()) {
        LOG("Error: docRequests missing or not an array");
        return nullptr;
    }
    auto docRequestsArray = docRequestsArrayItem->asArray();

    std::vector<DcqlCredentialQuery> credentialQueries;

    for (size_t i = 0; i < docRequestsArray->size(); ++i) {
        const auto& docRequestItem = docRequestsArray->get(i);
        if (!docRequestItem || !docRequestItem->asMap()) continue;
        auto docRequestMap = docRequestItem->asMap();

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

        auto registerClaim = [&](const std::string& ns, const std::string& elem, bool intent) -> std::string {
            std::string key = ns + "/" + elem;
            if (claimIdRegistry.find(key) != claimIdRegistry.end()) {
                return claimIdRegistry[key];
            }
            std::string id = "claim" + std::to_string(claimCounter++);
            std::vector<std::string> path = {ns, elem};

            dcqlClaims.push_back(DcqlRequestedClaim{id, {}, path, intent});
            claimIdRegistry[key] = id;
            return id;
        };

        // --- Handle AlternativeDataElements (DocRequestInfo) ---
        std::vector<std::vector<std::vector<std::string>>> logicalRequirements;

        const auto& nameSpacesItem = itemsRequestMap->get("nameSpaces");
        if (!nameSpacesItem || !nameSpacesItem->asMap()) continue;
        auto nameSpacesMap = nameSpacesItem->asMap();

        // Check for requestInfo / alternativeDataElements
        cppbor::Array* altDataElementsArray = nullptr;

        const auto& requestInfoItem = itemsRequestMap->get("requestInfo");
        if (requestInfoItem) {
            auto riMap = requestInfoItem->asMap();
            if (riMap) {
                const auto& altItem = riMap->get("alternativeDataElements");
                if (altItem) altDataElementsArray = altItem->asArray();
            }
        }

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

        credentialQueries.push_back(DcqlCredentialQuery(
                credId,
                "mso_mdoc",
                docType,
                {}, // vctValues
                dcqlClaims,
                claimSets
        ));
    }

    // --- Parse DeviceRequestInfo (UseCases) ---
    std::vector<DcqlCredentialSetQuery> credentialSetQueries;
    const auto& deviceRequestInfoItem = map->get("deviceRequestInfo");

    std::unique_ptr<cppbor::Item> drItem;
    cppbor::Map* drInfoMap = nullptr;

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

std::vector<Combination> MdocRequest::getCredentialCombinations(const CredentialDatabase* db) {
    auto result = dcqlQuery.execute((CredentialDatabase*)db);
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
        std::string payloadBase64 = jwtStr.substr(firstDot + 1, secondDot - firstDot - 1);
        std::string payload = base64UrlDecode(payloadBase64);
        dataJson = cJSON_Parse(payload.c_str());
    }

    cJSON* query = cJSON_GetObjectItem(dataJson, "dcql_query");
    auto dcqlQuery = DcqlQuery::parse(query);
    // dcqlQuery.log();

    return std::unique_ptr<OpenID4VPRequest> { new OpenID4VPRequest(
            protocolName,
            dcqlQuery
    )};
}