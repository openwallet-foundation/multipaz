#pragma once

#include <string>
#include <vector>
#include <memory>

extern "C" {
#include "cJSON.h"
}

#include "dcql.h"

// Structs for legacy/simple request parsing if needed, 
// though MdocRequest now delegates to DcqlQuery.
struct MdocRequestDataElement {
    std::string namespaceName;
    std::string dataElementName;
    bool intentToRetain;
};

struct VcRequestedClaim {
    std::string claimName;
};

struct Request {
    std::string protocol;
    Request(std::string protocol_) : protocol(protocol_) {}
    virtual ~Request() = default;
};

struct MdocRequest : public Request {
    MdocRequest(
            std::string protocol_,
            DcqlQuery dcqlQuery_
    ) : Request(protocol_), dcqlQuery(dcqlQuery_) {}

    // The logic is now encapsulated in this query object
    DcqlQuery dcqlQuery;

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