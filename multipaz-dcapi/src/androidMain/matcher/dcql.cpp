
#include "dcql.h"
#include "logger.h"
#include "paths.h"

extern "C" {
#include "credentialmanager.h"
}

DcqlQuery DcqlQuery::parse(cJSON* dcqlQuery) {
    auto dcqlCredentialQueries = std::vector<DcqlCredentialQuery>();
    auto dcqlCredentialSetQueries = std::vector<DcqlCredentialSetQuery>();

    cJSON* credentials = cJSON_GetObjectItem(dcqlQuery, "credentials");
    cJSON* credentialSets = cJSON_GetObjectItem(dcqlQuery, "credential_sets");

    cJSON* credential;
    cJSON_ArrayForEach(credential, credentials) {
        std::vector<std::string> vctValues;
        std::vector<DcqlRequestedClaim> requestedClaims;
        std::vector<DcqlClaimSet> claimSets;
        std::string mdocDocType;

        auto id = std::string(cJSON_GetStringValue(cJSON_GetObjectItem(credential, "id")));
        auto format = std::string(cJSON_GetStringValue(cJSON_GetObjectItem(credential, "format")));
        if (format == "mso_mdoc" || format == "mso_mdoc_zk") {
            cJSON* meta = cJSON_GetObjectItem(credential, "meta");
            mdocDocType = std::string(cJSON_GetStringValue(cJSON_GetObjectItem(meta, "doctype_value")));
        } else if (format == "dc+sd-jwt") {
            cJSON* meta = cJSON_GetObjectItem(credential, "meta");
            cJSON* vctValuesObj = cJSON_GetObjectItem(meta, "vct_values");
            cJSON* vctValueObj;
            cJSON_ArrayForEach(vctValueObj, vctValuesObj) {
                vctValues.push_back(std::string(cJSON_GetStringValue(vctValueObj)));
            }
        }

        cJSON* claim;
        cJSON_ArrayForEach(claim, cJSON_GetObjectItem(credential, "claims")) {
            std::string id = "";
            cJSON* idObj = cJSON_GetObjectItem(claim, "id");
            if (idObj != nullptr) {
                id = std::string(cJSON_GetStringValue(idObj));
            }
            std::vector<std::string> path;
            cJSON* pathElem;
            cJSON_ArrayForEach(pathElem, cJSON_GetObjectItem(claim, "path")) {
                path.push_back(std::string(cJSON_GetStringValue(pathElem)));
            }

            std::vector<std::string> values;
            cJSON* valuesObj = cJSON_GetObjectItem(claim, "values");
            if (valuesObj != nullptr) {
                // For value matching, the values in the database are already strings
                // so just convert to string and we'll match...
                cJSON* value;
                cJSON_ArrayForEach(value, valuesObj) {
                    if (cJSON_IsBool(value)) {
                        values.push_back(cJSON_IsTrue(value) ? "true" : "false");
                    } else if (cJSON_IsNumber(value)) {
                        // TODO: handle double?
                        values.push_back(std::to_string(value->valueint));
                    } else if (cJSON_IsString(value)) {
                        values.push_back(std::string(cJSON_GetStringValue(value)));
                    } else {
                        LOG("Warning: unhandled JSON type for value matching");
                    }
                }
            }

            auto intentToRetain = cJSON_IsTrue(cJSON_GetObjectItem(claim, "required"));

            auto requestedClaim = DcqlRequestedClaim(
                    id,
                    values,
                    path,
                    intentToRetain
            );
            requestedClaims.push_back(requestedClaim);
        }

        cJSON* claimSet;
        cJSON_ArrayForEach(claimSet, cJSON_GetObjectItem(credential, "claim_sets")) {
            std::vector<std::string> claimIdentifiers;
            cJSON* claimIdentifier;
            cJSON_ArrayForEach(claimIdentifier, claimSet) {
                claimIdentifiers.push_back(std::string(cJSON_GetStringValue(claimIdentifier)));
            }
            claimSets.push_back(DcqlClaimSet(claimIdentifiers));
        }

        dcqlCredentialQueries.push_back(DcqlCredentialQuery(
                id,
                format,
                mdocDocType,
                vctValues,
                requestedClaims,
                claimSets
        ));
    }

    cJSON* credentialSet;
    cJSON_ArrayForEach(credentialSet, credentialSets) {
        auto required = !cJSON_IsFalse(cJSON_GetObjectItem(credentialSet, "required"));
        auto options = std::vector<DcqlCredentialSetOption>();

        cJSON* option;
        cJSON_ArrayForEach(option, cJSON_GetObjectItem(credentialSet, "options")) {
            auto credentialIds = std::vector<std::string>();
            cJSON* id;
            cJSON_ArrayForEach(id, option) {
                credentialIds.push_back(cJSON_GetStringValue(id));
            }
            options.push_back(DcqlCredentialSetOption(credentialIds));
        }

        dcqlCredentialSetQueries.push_back(DcqlCredentialSetQuery(required, options));
    }

    return DcqlQuery(
            dcqlCredentialQueries,
            dcqlCredentialSetQueries
    );
}

DcqlRequestedClaim* DcqlCredentialQuery::findRequestedClaim(const std::string& claimId) {
    for (auto& requestedClaim : requestedClaims) {
        if (requestedClaim.id == claimId) {
            return &requestedClaim;
        }
    }
    return nullptr;
}

void DcqlQuery::log() {
#ifdef MATCHER_TEST_BUILD
    LOG("credentials = [");
    for (auto& q: dcqlCredentialQueries) {
        LOG("  {");
        LOG("    id %s", q.id.c_str());
        LOG("    format %s", q.format.c_str());
        LOG("    mdocDocType %s", q.mdocDocType.c_str());
        LOG("    vctValues %s", joinStrings(q.vctValues).c_str());
        LOG("    claims = [");
        for (auto &c : q.requestedClaims) {
            LOG("      {");
            LOG("        id %s", c.id.c_str());
            LOG("        values %s", joinStrings(c.values).c_str());
            LOG("        path %s", joinStrings(c.path).c_str());
            LOG("        intentToRetain %d", c.intentToRetain);
            LOG("      },");
        }
        LOG("    ],");
        LOG("    claimSets = [");
        for (auto &cs : q.claimSets) {
            LOG("      {");
            LOG("        claimIdentifiers %s", joinStrings(cs.claimIdentifiers).c_str());
            LOG("      },");
        }
        LOG("    ],");
        LOG("  },");
    }
    LOG("]");
    LOG("");
    LOG("credential_sets = [");
    for (auto& csq: dcqlCredentialSetQueries) {
        LOG("  {");
        LOG("    required %d", csq.required);
        LOG("    options = [");
        for (auto &o : csq.options) {
            LOG("      {");
            LOG("        credentialIds %s", joinStrings(o.credentialIds).c_str());
            LOG("      },");
        }
        LOG("    ],");
        LOG("  },");
    }
    LOG("]");
#endif // MATCHER_TEST_BUILD
}

struct QueryResponse {
    ~QueryResponse() {}
    DcqlCredentialQuery* query;
    std::vector<DcqlResponseCredentialSetOptionMemberMatch> matches;
};

bool CredentialSetOptionIsSatisfied(
        const DcqlCredentialSetOption& option,
        const std::map<std::string, QueryResponse>& credentialQueryIdToResponse
) {
    for (const auto& credentialId : option.credentialIds) {
        auto responseIt = credentialQueryIdToResponse.find(credentialId);
        if (responseIt == credentialQueryIdToResponse.end() || responseIt->second.matches.empty()) {
            return false;
        }
    }
    return true;
}

std::optional<DcqlResponse> DcqlQuery::execute(CredentialDatabase* credentialDatabase) {
    std::vector<DcqlResponseCredentialSet> credentialSets;

    std::map<std::string, QueryResponse> credentialQueryIdToResponse;
    for (auto& query: dcqlCredentialQueries) {
        std::vector<Credential*> credsSatifyingMeta;
        for (auto& cred: credentialDatabase->credentials) {
            if (query.format == "mso_mdoc" || query.format == "mso_mdoc_zk") {
                if (cred.mdocDocType == query.mdocDocType) {
                    credsSatifyingMeta.push_back(&cred);
                }
            } else if (query.format == "dc+sd-jwt") {
                if (std::find(query.vctValues.begin(), query.vctValues.end(), cred.vcVct) != query.vctValues.end()) {
                    credsSatifyingMeta.push_back(&cred);
                }
            }
        }

        std::vector<DcqlResponseCredentialSetOptionMemberMatch> matches;
        for (auto& cred : credsSatifyingMeta) {
            if (query.claimSets.size() == 0) {
                bool didNotMatch = false;
                auto matchingClaimValues = std::vector<Claim*>();
                for (auto& claim : query.requestedClaims) {
                    Claim* matchingCredentialClaim = cred->findMatchingClaim(claim);
                    if (matchingCredentialClaim != nullptr) {
                        matchingClaimValues.push_back(matchingCredentialClaim);
                    } else {
                        LOG("Error resolving requested claim with path %s", claim.joinPath().c_str());
                        didNotMatch = true;
                        break;
                    }
                }
                if (!didNotMatch) {
                    // All claims matched, we have a candidate
                    matches.push_back(DcqlResponseCredentialSetOptionMemberMatch(
                       cred,
                       matchingClaimValues
                    ));
                }
            } else {
                // Go through all the claim sets, one at a time, pick the first to match
                for (auto& claimSet : query.claimSets) {
                    bool didNotMatch = false;
                    auto matchingClaimValues = std::vector<Claim*>();
                    for (auto& claimId : claimSet.claimIdentifiers) {
                        DcqlRequestedClaim* requestedClaim = query.findRequestedClaim(claimId);
                        if (requestedClaim == nullptr) {
                            didNotMatch = true;
                            break;
                        }
                        Claim* matchingCredentialClaim = cred->findMatchingClaim(*requestedClaim);
                        if (matchingCredentialClaim != nullptr) {
                            matchingClaimValues.push_back(matchingCredentialClaim);
                        } else {
                            didNotMatch = true;
                            break;
                        }
                    }
                    if (!didNotMatch) {
                        // All claims matched, we have a candidate
                        matches.push_back(DcqlResponseCredentialSetOptionMemberMatch(
                                cred,
                                matchingClaimValues
                        ));
                        break;
                    }
                }
            }
        }
        credentialQueryIdToResponse[query.id] = QueryResponse(&query, matches);
    }

    if (dcqlCredentialSetQueries.empty()) {
        // From 6.4.2. Selecting Credentials:
        //
        //   If credential_sets is not provided, the Verifier requests presentations for
        //   all Credentials in credentials to be returned.
        //
        for (const auto& pair: credentialQueryIdToResponse) {
            if (pair.second.matches.empty()) {
                LOG("No matches for credential query with id %s", pair.second.query->id.c_str());
                return std::nullopt;
            }
            std::vector<DcqlResponseCredentialSetOptionMemberMatch> matches;
            for (const auto& match : pair.second.matches) {
                matches.push_back(DcqlResponseCredentialSetOptionMemberMatch(match.credential, match.claims));
            }
            std::vector<DcqlResponseCredentialSetOptionMember> members;
            members.push_back(DcqlResponseCredentialSetOptionMember(matches));
            std::vector<DcqlResponseCredentialSetOption> options;
            options.push_back(DcqlResponseCredentialSetOption(members));
            credentialSets.push_back(DcqlResponseCredentialSet(
                    false,
                    options
            ));
        }
        return DcqlResponse(credentialSets);
    }

    // From 6.4.2. Selecting Credentials:
    //
    //   Otherwise, the Verifier requests presentations of Credentials to be returned satisfying
    //
    //     - all of the Credential Set Queries in the credential_sets array where the
    //       required attribute is true or omitted, and
    //     - optionally, any of the other Credential Set Queries.
    //
    for (auto& csq : dcqlCredentialSetQueries) {
        // In this case, simply go through all the matches produced above and pick the
        // credentials from the highest preferred option. If none of them work, bail only
        // if the credential set was required.
        //
        bool satisfiedCsq = false;
        std::vector<DcqlResponseCredentialSetOption> options;
        for (auto& option : csq.options) {
            if (CredentialSetOptionIsSatisfied(option, credentialQueryIdToResponse)) {
                std::vector<DcqlResponseCredentialSetOptionMember> members;
                for (const auto& credentialId: option.credentialIds) {
                    const auto& response = credentialQueryIdToResponse[credentialId];
                    members.push_back(DcqlResponseCredentialSetOptionMember(response.matches));
                }
                options.push_back(DcqlResponseCredentialSetOption(members));
                satisfiedCsq = true;
            }
        }
        if (!satisfiedCsq && csq.required) {
            LOG("No credentials match required credential_set query");
            return std::nullopt;
        }
        credentialSets.push_back(DcqlResponseCredentialSet(
                !csq.required,
                options
        ));
    }
    return DcqlResponse(credentialSets);
}

DcqlResponseCredentialSet DcqlResponseCredentialSet::consolidateSingleMemberOptions() const {
    std::vector<DcqlResponseCredentialSetOption> nonSingleMemberOptions;
    std::vector<DcqlResponseCredentialSetOptionMemberMatch> singleMemberMatches;
    int numSingleMemberOptions = 0;
    for (const auto& option: options) {
        if (option.members.size() == 1) {
            for (const auto &match: option.members[0].matches) {
                singleMemberMatches.push_back(match);
            }
            numSingleMemberOptions++;
        } else {
            nonSingleMemberOptions.push_back(option);
        }
    }

    if (numSingleMemberOptions <= 1) {
        return *this;
    }
    std::vector<DcqlResponseCredentialSetOption> newOptions;
    std::vector<DcqlResponseCredentialSetOptionMember> members;
    members.push_back((DcqlResponseCredentialSetOptionMember(singleMemberMatches)));
    newOptions.push_back(DcqlResponseCredentialSetOption(members));
    for (const auto& o : nonSingleMemberOptions) {
        newOptions.push_back(o);
    }
    return DcqlResponseCredentialSet(optional,newOptions);
}


std::vector<DcqlResponseCredentialSet> DcqlResponse::consolidateCredentialSets() const {
    std::vector<DcqlResponseCredentialSet> consolidatedSets;
    for (const auto& credentialSet : credentialSets) {
        consolidatedSets.push_back(credentialSet.consolidateSingleMemberOptions());
    }
    return consolidatedSets;
}

std::vector<Combination> DcqlResponse::getCredentialCombinations() {
    std::vector<Combination> combinations;

    // First consolidate all single-member options into one...
    std::vector<DcqlResponseCredentialSet> consolidatedCredentialSets = consolidateCredentialSets();

    // ...then explode all combinations
    std::vector<int> maxPaths;
    for (const auto& credentialSet : consolidatedCredentialSets) {
        // If a documentSet is optional, it's an extra combination we tag at the end
        maxPaths.push_back(credentialSet.options.size() + (credentialSet.optional ? 1 : 0));
    }
    std::vector<std::vector<int>> pathCombinations = generateAllPaths(maxPaths);

    int number = 0;
    for (const auto& pathCombination : pathCombinations) {
        std::vector<CombinationElement> elements;
        int credentialSetNum = 0;
        for (const auto& credentialSet : consolidatedCredentialSets) {
            bool omitDocumentSet = (pathCombination[credentialSetNum] == credentialSet.options.size());
            if (!omitDocumentSet) {
                const auto& option = credentialSet.options[pathCombination[credentialSetNum]];

                for (const auto& member : option.members) {
                    std::vector<CredentialPresentment> cmatches;
                    for (const auto& match : member.matches) {
                        cmatches.push_back(CredentialPresentment(match.credential, match.claims));
                    }
                    elements.push_back(CombinationElement(cmatches));
                }
            }
            credentialSetNum++;
        }
        combinations.push_back(Combination(number++, elements));
    }
    return combinations;
}
