
#pragma once

#include <stdint.h>
#include <string>
#include <vector>
#include <map>

//#include "Request.h"
struct Request;

struct Claim;

struct DcqlRequestedClaim;

struct Credential {
    std::string title;
    std::string subtitle;
    std::vector<uint8_t> bitmap;

    std::string documentId;

    // This is the empty string if not available as an ISO mdoc.
    std::string mdocDocType;

    // This is the empty string if not available as a VC.
    std::string vcVct;

    // Maps from claimName to Claim.
    std::map<std::string, Claim> claims;

    Claim* findMatchingClaim(const DcqlRequestedClaim& claim);

    bool matchesRequest(const Request& request);

    void addCredentialToPicker(const Request& request);
};

struct Claim {
    ~Claim() {}
    // For Json-based credentials the claimName is the concatenation of all paths, using "." and for
    // Mdoc-based credentials it's namespaceName.dataElementName
    std::string claimName;
    std::string displayName;
    std::string value;
    std::string matchValue;
};

struct CredentialDatabase {
    CredentialDatabase(const uint8_t* encodedDatabase, size_t encodedDatabaseLength);
    std::vector<std::string> protocols;
    std::vector<Credential> credentials;
};

struct CredentialPresentment {
    Credential* credential;
    std::vector<Claim*> claims;
};

struct CombinationElement {
    std::vector<CredentialPresentment> matches;
};

struct Combination {
    int combinationNumber;
    std::vector<CombinationElement> elements;

    void addToCredmanPicker(const Request& request) const;
};
