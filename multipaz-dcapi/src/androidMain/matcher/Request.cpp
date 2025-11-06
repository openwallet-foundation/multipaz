
#include <map>

#include "base64.h"
#include "cppbor_parse.h"

#include "Request.h"
#include "logger.h"

std::string base64UrlDecode(const std::string& data) {
    // from_base64() doesn't handle strings without padding so we need
    // to manually add padding
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

std::unique_ptr<MdocRequest> MdocRequest::parseMdocApi(const std::string& protocolName, cJSON* dataJson) {
    cJSON* deviceRequestJson = cJSON_GetObjectItem(dataJson, "deviceRequest");
    std::string deviceRequestBase64 = std::string(cJSON_GetStringValue(deviceRequestJson));

    std::string docTypeValue;
    auto dataElements = std::vector<MdocRequestDataElement>();

    std::string deviceRequest = base64UrlDecode(deviceRequestBase64);
    auto [item, pos, message] =
            cppbor::parse((const uint8_t*) deviceRequest.data(), deviceRequest.size());

    auto map = item->asMap();
    auto doc_requests = map->get("docRequests")->asArray();

    // We only consider the first DocRequest...
    auto doc_request = doc_requests->get(0)->asMap();
    auto items_request_bytes = doc_request->get("itemsRequest")->asSemanticTag()->asBstr();
    auto [items_request, pos2, message2]
        = cppbor::parse(items_request_bytes->value());
    auto items_request_map = items_request->asMap();
    auto doc_type = items_request_map->get("docType")->asTstr();

    docTypeValue = doc_type->value();

    auto namespaces = items_request_map->get("nameSpaces")->asMap();
    for (auto j = namespaces->begin(); j != namespaces->end(); j++) {
        auto namespaceName = j->first->asTstr()->value();
        auto deMap = j->second->asMap();
        for (auto k = deMap->begin(); k != deMap->end(); k++) {
            auto dataElementName = k->first->asTstr()->value();
            auto intentToRetain = k->second->asBool()->value();
            dataElements.push_back(MdocRequestDataElement(namespaceName, dataElementName, intentToRetain));
        }
    }

    return std::unique_ptr<MdocRequest> { new MdocRequest(protocolName, docTypeValue, dataElements) };
}

std::vector<Combination> MdocRequest::getCredentialCombinations(const CredentialDatabase* db) {
    std::vector<Combination> combinations;

    // Since we only support a single docRequest (for now) this is easy...
    std::vector<CredentialPresentment> matches;
    if (!docType.empty()) {
        for (const auto& credential : db->credentials) {
            std::vector<Claim*> claimValues;
            if (credential.mdocDocType == docType) {
                // Semantics is that we match if at least one of the requested data elements
                // exist in the credential
                for (const auto &requestedDataElement: dataElements) {
                    auto combinedName =
                            requestedDataElement.namespaceName + "." + requestedDataElement.dataElementName;
                    const auto& claim = credential.claims.find(combinedName);
                    if (claim != credential.claims.end()) {
                        claimValues.push_back((Claim*) &(claim->second));
                    }
                }
                if (claimValues.size() > 0) {
                    matches.push_back(CredentialPresentment(
                            (Credential *) &credential,
                            claimValues
                    ));
                }
            }
        }
    }
    std::vector<CombinationElement> elements;
    elements.push_back(CombinationElement(matches));
    combinations.push_back(Combination(0, elements));
    return combinations;
}


std::unique_ptr<OpenID4VPRequest> OpenID4VPRequest::parseOpenID4VP(cJSON* dataJson, std::string protocolName) {
    std::string docTypeValue = "";
    auto dataElements = std::vector<MdocRequestDataElement>();
    std::vector<std::string> vctValues;
    auto vcClaims = std::vector<VcRequestedClaim>();
    auto dcqlCredentialQueries = std::vector<DcqlCredentialQuery>();
    auto dcqlCredentialSetQueries = std::vector<DcqlCredentialSetQuery>();

    // Handle signed request... the payload of the JWS is between the first and second '.' characters.
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
    dcqlQuery.log();

    return std::unique_ptr<OpenID4VPRequest> { new OpenID4VPRequest(
            protocolName,
            dcqlQuery
    )};
}
