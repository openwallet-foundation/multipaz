
#pragma once

#include <string>
#include <vector>
#include <optional>

#include "CredentialDatabase.h"

extern "C" {
#include "cJSON.h"
}

struct DcqlRequestedClaim {
    std::string id;
    std::vector<std::string> values;
    std::vector<std::string> path;

    // Only relevant for ISO mdoc
    bool intentToRetain;

    std::string joinPath() const {
        std::string ret;
        for (const auto& pathElem : path) {
            if (!ret.empty()) {
                ret += "." + pathElem;
            } else {
                ret = pathElem;
            }
        }
        return ret;
    }
};

struct DcqlClaimSet {
    std::vector<std::string> claimIdentifiers;
};

struct DcqlCredentialQuery {
    std::string id;
    std::string format;

    std::string mdocDocType;
    std::vector<std::string> vctValues;

    std::vector<DcqlRequestedClaim> requestedClaims;
    std::vector<DcqlClaimSet> claimSets;

    DcqlRequestedClaim* findRequestedClaim(const std::string& claimId);
};

struct DcqlResponse;

struct DcqlCredentialSetOption {
    std::vector<std::string> credentialIds;
};

struct DcqlCredentialSetQuery {
    bool required;
    std::vector<DcqlCredentialSetOption> options;
};

struct DcqlQuery {
    std::vector<DcqlCredentialQuery> dcqlCredentialQueries;
    std::vector<DcqlCredentialSetQuery> dcqlCredentialSetQueries;

    void log();

    std::optional<DcqlResponse> execute(CredentialDatabase* credentialDatabase);

    static DcqlQuery parse(cJSON* dcqlQuery);
};

struct DcqlResponseCredentialSetOptionMemberMatch {
    Credential* credential;
    std::vector<Claim*> claims;
};

struct DcqlResponseCredentialSetOptionMember {
    std::vector<DcqlResponseCredentialSetOptionMemberMatch> matches;
};

struct DcqlResponseCredentialSetOption {
    std::vector<DcqlResponseCredentialSetOptionMember> members;
};

struct DcqlResponseCredentialSet {
    bool optional;
    std::vector<DcqlResponseCredentialSetOption> options;

    DcqlResponseCredentialSet consolidateSingleMemberOptions() const;
};

struct DcqlResponse {
    std::vector<DcqlResponseCredentialSet> credentialSets;

    std::vector<DcqlResponseCredentialSet> consolidateCredentialSets() const;
    std::vector<Combination> getCredentialCombinations();
};
