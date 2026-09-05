
#pragma once

#include <stdint.h>
#include <string>
#include <vector>
#include <map>

//#include "Request.h"
struct Request;

struct DcqlRequestedClaim;

struct Claim {
    Claim() = default;
    Claim(std::string claimName_, std::string displayName_, std::string value_, std::string matchValue_, bool isDeviceSigned_ = false)
        : claimName(std::move(claimName_)),
          displayName(std::move(displayName_)),
          value(std::move(value_)),
          matchValue(std::move(matchValue_)),
          isDeviceSigned(isDeviceSigned_) {}
    ~Claim() {}
    // For Json-based credentials the claimName is the concatenation of all paths, using "." and for
    // Mdoc-based credentials it's namespaceName.dataElementName
    std::string claimName;
    std::string displayName;
    std::string value;
    std::string matchValue;
    bool isDeviceSigned = false;
};

struct Credential {
    std::string title;
    std::string subtitle;
    std::vector<uint8_t> bitmap;

    std::string documentId;

    // This is the empty string if not available as an ISO mdoc.
    std::string mdocDocType;

    // This is the empty string if not available as a VC.
    std::string vcVct;

    // This is the set of protocols the credential can be exported on.
    std::vector<std::string> protocols;

    // Issuer identifiers (AuthorityKeyIdentifiers)
    std::vector<std::vector<uint8_t>> issuerIdentifiers;

    // Reader identifiers (AuthorityKeyIdentifiers)
    std::vector<std::vector<uint8_t>> readerIdentifiers;

    // Key authorizations (for mdoc device-signed data elements)
    std::vector<std::string> keyAuthorizedNamespaces;
    std::map<std::string, std::vector<std::string>> keyAuthorizedDataElements;

    // Maps from claimName to Claim.
    std::map<std::string, Claim> claims;

    // Claims dynamically created for authorized device-signed data elements
    mutable std::map<std::string, Claim> dynamicDeviceClaims;

    Credential(
        std::string title_,
        std::string subtitle_,
        std::vector<uint8_t> bitmap_,
        std::string documentId_,
        std::string mdocDocType_,
        std::string vcVct_,
        std::vector<std::string> protocols_,
        std::vector<std::vector<uint8_t>> issuerIdentifiers_,
        std::vector<std::vector<uint8_t>> readerIdentifiers_,
        std::vector<std::string> keyAuthorizedNamespaces_,
        std::map<std::string, std::vector<std::string>> keyAuthorizedDataElements_,
        std::map<std::string, Claim> claims_
    ) : title(std::move(title_)),
        subtitle(std::move(subtitle_)),
        bitmap(std::move(bitmap_)),
        documentId(std::move(documentId_)),
        mdocDocType(std::move(mdocDocType_)),
        vcVct(std::move(vcVct_)),
        protocols(std::move(protocols_)),
        issuerIdentifiers(std::move(issuerIdentifiers_)),
        readerIdentifiers(std::move(readerIdentifiers_)),
        keyAuthorizedNamespaces(std::move(keyAuthorizedNamespaces_)),
        keyAuthorizedDataElements(std::move(keyAuthorizedDataElements_)),
        claims(std::move(claims_)) {}

    Claim* findMatchingClaim(const DcqlRequestedClaim& claim);

    bool supportsProtocol(const std::string& protocol);

    bool matchesRequest(const Request& request);

    void addCredentialToPicker(const Request& request);
};

struct CredentialDatabase {
    CredentialDatabase(const uint8_t* encodedDatabase, size_t encodedDatabaseLength);
    //std::vector<std::string> protocols;
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
