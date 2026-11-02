
#include "CredentialDatabase.h"
#include "Request.h"

extern "C" {
#include "credentialmanager.h"
}

#include "cppbor.h"
#include "cppbor_parse.h"
#include "logger.h"

CredentialDatabase::CredentialDatabase(const uint8_t* encodedDatabase, size_t encodedDatabaseLength) {
    auto [item, pos, message] =
            cppbor::parse(encodedDatabase, encodedDatabaseLength);
    if (item == nullptr) {
        printf("Error parsing CBOR: %s\n", message.c_str());
        return;
    }
    auto topMap = item->asMap();

    std::vector<std::string> topProtocols;
    auto protocolsArray = topMap->get("protocols")->asArray();
    for (auto p = protocolsArray->begin(); p != protocolsArray->end(); ++p) {
        topProtocols.push_back((*p)->asTstr()->value());
    }

    auto credentialsArray = topMap->get("credentials")->asArray();
    for (auto i = credentialsArray->begin(); i != credentialsArray->end(); ++i) {
        auto cred = (*i)->asMap();
        auto title = cred->get("title")->asTstr()->value();
        auto subtitle = cred->get("subtitle")->asTstr()->value();
        auto bitmap = cred->get("bitmap")->asBstr()->value();
        std::string noClaimsRequestedText = "";
        auto& noClaimsRequestedTextPtr = cred->get("noClaimsRequestedText");
        if (noClaimsRequestedTextPtr != nullptr && noClaimsRequestedTextPtr->asTstr() != nullptr) {
            noClaimsRequestedText = noClaimsRequestedTextPtr->asTstr()->value();
        }

        std::string documentId = "";
        std::string mdocDoctype = "";
        std::string vcVct = "";
        std::vector<std::string> vcExtendedVcts;
        std::vector<std::string> docProtocols = topProtocols;
        std::vector<std::vector<uint8_t>> issuerIdentifiers;
        std::vector<std::vector<uint8_t>> readerIdentifiers;
        std::vector<std::string> keyAuthorizedNamespaces;
        std::map<std::string, std::vector<std::string>> keyAuthorizedDataElements;
        bool supportsPortraitImageEquivalence = false;
        bool omitClaims = false;
        std::map<std::string, std::string> claimDisplayNames;
        std::map resultingClaims = std::map<std::string, Claim>();

        auto& docProtocolsPtr = cred->get("protocols");
        if (docProtocolsPtr != nullptr) {
            auto docProtocolsArray = docProtocolsPtr->asArray();
            if (docProtocolsArray != nullptr) {
                docProtocols.clear();
                for (auto p = docProtocolsArray->begin(); p != docProtocolsArray->end(); ++p) {
                    docProtocols.push_back((*p)->asTstr()->value());
                }
            }
        }

        auto& mdocPtr = cred->get("mdoc");
        if (mdocPtr != nullptr) {
            auto mdoc = mdocPtr->asMap();
            documentId = mdoc->get("documentId")->asTstr()->value();
            mdocDoctype = mdoc->get("docType")->asTstr()->value();

            const auto& piePtr = mdoc->get("supportsPortraitImageEquivalence");
            if (piePtr != nullptr && piePtr->asBool() != nullptr) {
                supportsPortraitImageEquivalence = piePtr->asBool()->value();
            }

            const auto& omitClaimsPtr = mdoc->get("omitClaims");
            if (omitClaimsPtr != nullptr && omitClaimsPtr->asBool() != nullptr) {
                omitClaims = omitClaimsPtr->asBool()->value();
            }

            const auto& claimDisplayNamesPtr = mdoc->get("claimDisplayNames");
            if (claimDisplayNamesPtr != nullptr && claimDisplayNamesPtr->asMap() != nullptr) {
                auto cdnMap = claimDisplayNamesPtr->asMap();
                for (auto it = cdnMap->begin(); it != cdnMap->end(); ++it) {
                    if (it->first->asTstr() != nullptr && it->second->asTstr() != nullptr) {
                        claimDisplayNames[it->first->asTstr()->value()] = it->second->asTstr()->value();
                    }
                }
            }

            const auto& issuerIdentifiersPtr = mdoc->get("issuerIdentifiers");
            if (issuerIdentifiersPtr != nullptr && issuerIdentifiersPtr->asArray() != nullptr) {
                auto arr = issuerIdentifiersPtr->asArray();
                for (auto it = arr->begin(); it != arr->end(); ++it) {
                    if ((*it)->asBstr() != nullptr) {
                        issuerIdentifiers.push_back((*it)->asBstr()->value());
                    }
                }
            }

            const auto& readerIdentifiersPtr = mdoc->get("readerIdentifiers");
            if (readerIdentifiersPtr != nullptr && readerIdentifiersPtr->asArray() != nullptr) {
                auto arr = readerIdentifiersPtr->asArray();
                for (auto it = arr->begin(); it != arr->end(); ++it) {
                    if ((*it)->asBstr() != nullptr) {
                        readerIdentifiers.push_back((*it)->asBstr()->value());
                    }
                }
            }

            const auto& keyAuthPtr = mdoc->get("keyAuthorizations");
            if (keyAuthPtr != nullptr && keyAuthPtr->asMap() != nullptr) {
                auto keyAuthMap = keyAuthPtr->asMap();
                const auto& nsArrPtr = keyAuthMap->get("nameSpaces");
                if (nsArrPtr != nullptr && nsArrPtr->asArray() != nullptr) {
                    auto arr = nsArrPtr->asArray();
                    for (auto it = arr->begin(); it != arr->end(); ++it) {
                        if ((*it)->asTstr() != nullptr) {
                            keyAuthorizedNamespaces.push_back((*it)->asTstr()->value());
                        }
                    }
                }
                const auto& deMapPtr = keyAuthMap->get("dataElements");
                if (deMapPtr != nullptr && deMapPtr->asMap() != nullptr) {
                    auto deMap = deMapPtr->asMap();
                    for (auto it = deMap->begin(); it != deMap->end(); ++it) {
                        std::string nsName = it->first->asTstr()->value();
                        auto deList = it->second->asArray();
                        if (deList != nullptr) {
                            for (auto deIt = deList->begin(); deIt != deList->end(); ++deIt) {
                                if ((*deIt)->asTstr() != nullptr) {
                                    keyAuthorizedDataElements[nsName].push_back((*deIt)->asTstr()->value());
                                }
                            }
                        }
                    }
                }
            }

            const auto& namespacesPtr = mdoc->get("namespaces");
            if (namespacesPtr != nullptr && namespacesPtr->asMap() != nullptr) {
                auto namespaces = namespacesPtr->asMap();
                for (auto j = namespaces->begin(); j != namespaces->end(); ++j) {
                    auto namespaceName = j->first->asTstr()->value();
                    auto dataElementsMap = j->second->asMap();

                    for (auto k = dataElementsMap->begin(); k != dataElementsMap->end(); ++k) {
                        auto dataElementName = k->first->asTstr()->value();
                        auto dataElementDetailsArray = k->second->asArray();
                        auto displayName = dataElementDetailsArray->get(0)->asTstr()->value();
                        auto value = dataElementDetailsArray->get(1)->asTstr()->value();
                        auto matchValue = dataElementDetailsArray->get(2)->asTstr()->value();

                        auto combinedName = namespaceName + "." + dataElementName;
                        resultingClaims[combinedName] = Claim(combinedName, displayName, value, matchValue);
                    }
                }
            }
        }

        auto& sdjwtPtr = cred->get("sdjwt");
        if (sdjwtPtr != nullptr) {
            auto sdjwt = sdjwtPtr->asMap();
            documentId = sdjwt->get("documentId")->asTstr()->value();
            vcVct = sdjwt->get("vct")->asTstr()->value();

            const auto& extendedVctsPtr = sdjwt->get("extendedVcts");
            if (extendedVctsPtr != nullptr && extendedVctsPtr->asArray() != nullptr) {
                auto arr = extendedVctsPtr->asArray();
                for (auto it = arr->begin(); it != arr->end(); ++it) {
                    if ((*it)->asTstr() != nullptr) {
                        vcExtendedVcts.push_back((*it)->asTstr()->value());
                    }
                }
            }

            const auto& omitClaimsPtr = sdjwt->get("omitClaims");
            if (omitClaimsPtr != nullptr && omitClaimsPtr->asBool() != nullptr) {
                omitClaims = omitClaimsPtr->asBool()->value();
            }

            const auto& claimDisplayNamesPtr = sdjwt->get("claimDisplayNames");
            if (claimDisplayNamesPtr != nullptr && claimDisplayNamesPtr->asMap() != nullptr) {
                auto cdnMap = claimDisplayNamesPtr->asMap();
                for (auto it = cdnMap->begin(); it != cdnMap->end(); ++it) {
                    if (it->first->asTstr() != nullptr && it->second->asTstr() != nullptr) {
                        claimDisplayNames[it->first->asTstr()->value()] = it->second->asTstr()->value();
                    }
                }
            }

            const auto& issuerIdentifiersPtr = sdjwt->get("issuerIdentifiers");
            if (issuerIdentifiersPtr != nullptr && issuerIdentifiersPtr->asArray() != nullptr) {
                auto arr = issuerIdentifiersPtr->asArray();
                for (auto it = arr->begin(); it != arr->end(); ++it) {
                    if ((*it)->asBstr() != nullptr) {
                        issuerIdentifiers.push_back((*it)->asBstr()->value());
                    }
                }
            }

            const auto& readerIdentifiersPtr = sdjwt->get("readerIdentifiers");
            if (readerIdentifiersPtr != nullptr && readerIdentifiersPtr->asArray() != nullptr) {
                auto arr = readerIdentifiersPtr->asArray();
                for (auto it = arr->begin(); it != arr->end(); ++it) {
                    if ((*it)->asBstr() != nullptr) {
                        readerIdentifiers.push_back((*it)->asBstr()->value());
                    }
                }
            }

            const auto& claimsPtr = sdjwt->get("claims");
            if (claimsPtr != nullptr && claimsPtr->asMap() != nullptr) {
                auto claims = claimsPtr->asMap();
                for (auto j = claims->begin(); j != claims->end(); ++j) {
                    auto claimName = j->first->asTstr()->value();
                    auto claimDetailsArray = j->second->asArray();
                    auto displayName = claimDetailsArray->get(0)->asTstr()->value();
                    auto value = claimDetailsArray->get(1)->asTstr()->value();
                    auto matchValue = claimDetailsArray->get(2)->asTstr()->value();

                    resultingClaims[claimName] = Claim(claimName, displayName, value, matchValue);
                }
            }
        }

        credentials.push_back(
            Credential(
                title, subtitle, bitmap,
                documentId,
                mdocDoctype,
                vcVct,
                vcExtendedVcts,
                docProtocols,
                issuerIdentifiers,
                readerIdentifiers,
                keyAuthorizedNamespaces,
                keyAuthorizedDataElements,
                resultingClaims,
                supportsPortraitImageEquivalence,
                omitClaims,
                claimDisplayNames,
                noClaimsRequestedText
            )
        );
    }
}

bool Credential::supportsProtocol(const std::string& protocol) {
    for (const auto& p: protocols) {
        if (p == protocol) {
            return true;
        }
    }
    return false;
}

static bool isNumericSegment(const std::string& seg) {
    if (seg.empty()) {
        return false;
    }
    for (char c : seg) {
        if (c < '0' || c > '9') {
            return false;
        }
    }
    return true;
}

std::string Credential::lookupClaimDisplayName(const std::vector<std::string>& path) const {
    if (path.empty()) {
        return "";
    }
    std::string joinedPath;
    for (size_t i = 0; i < path.size(); ++i) {
        if (i > 0) {
            joinedPath += ".";
        }
        joinedPath += path[i];
    }
    auto it = claimDisplayNames.find(joinedPath);
    if (it != claimDisplayNames.end()) {
        return it->second;
    }
    if (!vcVct.empty()) {
        std::string strippedPath;
        std::string lastStringSegment;
        for (const auto& seg : path) {
            if (seg == "null" || isNumericSegment(seg)) {
                continue;
            }
            if (!strippedPath.empty()) {
                strippedPath += ".";
            }
            strippedPath += seg;
            lastStringSegment = seg;
        }
        if (!strippedPath.empty()) {
            auto strippedIt = claimDisplayNames.find(strippedPath);
            if (strippedIt != claimDisplayNames.end()) {
                return strippedIt->second;
            }
        }
        if (!lastStringSegment.empty()) {
            return lastStringSegment;
        }
    }
    return path.back();
}

Claim* Credential::findMatchingClaim(const DcqlRequestedClaim& requestedClaim) {
    auto joinedPath = requestedClaim.joinPath();
    if (omitClaims) {
        bool isDeviceSigned = false;
        if (requestedClaim.path.size() == 2) {
            const std::string& ns = requestedClaim.path[0];
            const std::string& elem = requestedClaim.path[1];
            if (ns == "org.iso.transactiondata") {
                isDeviceSigned = true;
            } else if (ns == "org.iso.23220.5.1" && elem == "CHV_1") {
                isDeviceSigned = true;
            }
        }
        auto dynIt = dynamicDeviceClaims.find(joinedPath);
        if (dynIt == dynamicDeviceClaims.end()) {
            std::string displayName = isDeviceSigned ? "" : lookupClaimDisplayName(requestedClaim.path);
            auto [newIt, _] = dynamicDeviceClaims.emplace(
                joinedPath,
                Claim(joinedPath, displayName, "", "", isDeviceSigned, /* omitValue = */ true)
            );
            return &(newIt->second);
        }
        return &(dynIt->second);
    }

    auto ret = claims.find(joinedPath);
    if (ret != claims.end()) {
        // Perform value matching, if requested
        if (!requestedClaim.values.empty()) {
            const std::vector<std::string>& values = requestedClaim.values;
            if (std::find(values.begin(), values.end(), ret->second.matchValue) == values.end()) {
                return nullptr;
            }
        }
        return &(ret->second);
    }

    if (!requestedClaim.values.empty()) {
        return nullptr;
    }

    // Check KeyAuthorizations for device-signed data elements
    if (requestedClaim.path.size() == 2) {
        const std::string& ns = requestedClaim.path[0];
        const std::string& elem = requestedClaim.path[1];
        bool authorized = false;
        if (!vcVct.empty() && ns == "org.iso.transactiondata") {
            authorized = true;
        } else if (ns == "org.iso.23220.5.1") {
            if (elem == "CHV_1" && supportsPortraitImageEquivalence) {
                if (std::find(keyAuthorizedNamespaces.begin(), keyAuthorizedNamespaces.end(), ns) != keyAuthorizedNamespaces.end()) {
                    authorized = true;
                } else {
                    auto it = keyAuthorizedDataElements.find(ns);
                    if (it != keyAuthorizedDataElements.end()) {
                        if (std::find(it->second.begin(), it->second.end(), elem) != it->second.end()) {
                            authorized = true;
                        }
                    }
                }
            }
        } else if (std::find(keyAuthorizedNamespaces.begin(), keyAuthorizedNamespaces.end(), ns) != keyAuthorizedNamespaces.end()) {
            authorized = true;
        } else {
            auto it = keyAuthorizedDataElements.find(ns);
            if (it != keyAuthorizedDataElements.end()) {
                if (std::find(it->second.begin(), it->second.end(), elem) != it->second.end()) {
                    authorized = true;
                }
            }
        }
        if (authorized) {
            auto dynIt = dynamicDeviceClaims.find(joinedPath);
            if (dynIt == dynamicDeviceClaims.end()) {
                auto [newIt, _] = dynamicDeviceClaims.emplace(
                    joinedPath,
                    Claim(joinedPath, "", "", "", /* isDeviceSigned = */ true)
                );
                return &(newIt->second);
            }
            return &(dynIt->second);
        }
    }

    return nullptr;
}

// The text of the field shown when a request names no claim to show, if the app set none.
static const char* DEFAULT_NO_CLAIMS_REQUESTED_TEXT = "No data requested";

void Combination::addToCredmanPicker(const Request& request) const {
    uint32_t credmanRuntimeVersion = 0;
    GetWasmVersion(&credmanRuntimeVersion);

    std::string setIdStr = std::to_string(combinationNumber) + " " + request.protocol;
    char* setId = strdup(setIdStr.c_str());
    int setLength = elements.size();

    if (credmanRuntimeVersion >= 2) {
        AddEntrySet(setId, setLength);
    }

    int setIndex = 0;
    for (const auto& element : elements) {
        for (const auto& match: element.matches) {
            Credential *credential = match.credential;
            std::string entryIdStr =
                    std::to_string(combinationNumber) + " " + request.protocol + " " +
                    credential->documentId;
            char *entryId = (char *) strdup(entryIdStr.c_str());

            void *icon = nullptr;
            if (credential->bitmap.size() > 0) {
                icon = malloc(credential->bitmap.size());
                memcpy(icon, credential->bitmap.data(), credential->bitmap.size());
            }
            if (credmanRuntimeVersion >= 2) {
                ::AddEntryToSet(
                        entryId,
                        (char *) icon,
                        credential->bitmap.size(),
                        (char *) strdup(credential->title.c_str()),
                        (char *) strdup(credential->subtitle.c_str()),
                        nullptr,
                        nullptr,
                        nullptr,
                        setId,
                        setIndex
                );
            } else {
                ::AddStringIdEntry(
                        entryId,
                        (char *) icon,
                        credential->bitmap.size(),
                        (char *) strdup(credential->title.c_str()),
                        (char *) strdup(credential->subtitle.c_str()),
                        nullptr,
                        nullptr
                );
            }

            int fieldsAdded = 0;
            for (const auto &claim: match.claims) {
                if (claim->isDeviceSigned) {
                    continue;
                }
                fieldsAdded++;
                char *fieldValue = claim->omitValue ? nullptr : strdup(claim->value.c_str());
                if (credmanRuntimeVersion >= 2) {
                    ::AddFieldToEntrySet(entryId,
                                         strdup(claim->displayName.c_str()),
                                         fieldValue,
                                         setId,
                                         setIndex
                    );
                } else {
                    ::AddFieldForStringIdEntry(entryId,
                                               strdup(claim->displayName.c_str()),
                                               fieldValue
                    );
                }
            }

            // Credential Manager does not show an entry without a field, and then tells the user
            // their info wasn't found. That is the case for a request naming no claims at all, or
            // only device-signed ones, so the entry gets one field saying no data is requested.
            if (fieldsAdded == 0) {
                const char* text = credential->noClaimsRequestedText.empty()
                        ? DEFAULT_NO_CLAIMS_REQUESTED_TEXT
                        : credential->noClaimsRequestedText.c_str();
                if (credmanRuntimeVersion >= 2) {
                    ::AddFieldToEntrySet(entryId, strdup(text), nullptr, setId, setIndex);
                } else {
                    ::AddFieldForStringIdEntry(entryId, strdup(text), nullptr);
                }
            }

            if (credmanRuntimeVersion < 2) {
                break;
            }
        }

        setIndex++;
        if (credmanRuntimeVersion < 2) {
            break;
        }
    }
}


