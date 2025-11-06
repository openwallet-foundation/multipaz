
#pragma once

#include <string>
#include <vector>

extern "C" {
#include "cJSON.h"
}

#include "dcql.h"

struct MdocRequestDataElement {
    std::string namespaceName;
    std::string dataElementName;
    bool intentToRetain;
};

struct VcRequestedClaim {
    // TODO: support path
    std::string claimName;
};

struct Request {
    std::string protocol;
};

struct MdocRequest : public Request {
    MdocRequest(
        std::string protocol_,
        std::string docType_,
        std::vector<MdocRequestDataElement> dataElements_
    ) : Request(protocol_), docType(docType_), dataElements(dataElements_) {}

    std::string docType;
    std::vector<MdocRequestDataElement> dataElements;

    std::vector<Combination> getCredentialCombinations(const CredentialDatabase* db);

    static std::unique_ptr<MdocRequest> parseMdocApi(const std::string& protocolName, cJSON *requestJson);
};

struct OpenID4VPRequest : public Request {
    OpenID4VPRequest(
        std::string protocol_,
        DcqlQuery dcqlQuery_
    ): Request(protocol_), dclqQuery(dcqlQuery_) {}

    DcqlQuery dclqQuery;

    static std::unique_ptr<OpenID4VPRequest> parseOpenID4VP(cJSON *requestJson, std::string protocolValue);
};
