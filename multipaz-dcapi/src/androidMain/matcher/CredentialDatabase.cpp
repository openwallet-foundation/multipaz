
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

    auto protocolsArray = topMap->get("protocols")->asArray();
    for (auto p = protocolsArray->begin(); p != protocolsArray->end(); ++p) {
        protocols.push_back((*p)->asTstr()->value());
    }

    auto credentialsArray = topMap->get("credentials")->asArray();
    for (auto i = credentialsArray->begin(); i != credentialsArray->end(); ++i) {
        auto cred = (*i)->asMap();
        auto title = cred->get("title")->asTstr()->value();
        auto subtitle = cred->get("subtitle")->asTstr()->value();
        auto bitmap = cred->get("bitmap")->asBstr()->value();

        std::string documentId = "";
        std::string mdocDoctype = "";
        std::string vcVct = "";
        std::map resultingClaims = std::map<std::string, Claim>();

        auto& mdocPtr = cred->get("mdoc");
        if (mdocPtr != nullptr) {
            auto mdoc = mdocPtr->asMap();
            documentId = mdoc->get("documentId")->asTstr()->value();
            mdocDoctype = mdoc->get("docType")->asTstr()->value();

            auto namespaces = mdoc->get("namespaces")->asMap();
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

        auto& sdjwtPtr = cred->get("sdjwt");
        if (sdjwtPtr != nullptr) {
            auto sdjwt = sdjwtPtr->asMap();
            documentId = sdjwt->get("documentId")->asTstr()->value();
            vcVct = sdjwt->get("vct")->asTstr()->value();

            auto claims = sdjwt->get("claims")->asMap();
            for (auto j = claims->begin(); j != claims->end(); ++j) {
                auto claimName = j->first->asTstr()->value();
                auto claimDetailsArray = j->second->asArray();
                auto displayName = claimDetailsArray->get(0)->asTstr()->value();
                auto value = claimDetailsArray->get(1)->asTstr()->value();
                auto matchValue = claimDetailsArray->get(2)->asTstr()->value();

                resultingClaims[claimName] = Claim(claimName, displayName, value, matchValue);
            }
        }

        credentials.push_back(
            Credential(
                title, subtitle, bitmap,
                documentId,
                mdocDoctype,
                vcVct,
                resultingClaims
            )
        );
    }
}

Claim* Credential::findMatchingClaim(const DcqlRequestedClaim& requestedClaim) {
    auto joinedPath = requestedClaim.joinPath();
    auto ret = claims.find(joinedPath);
    if (ret == claims.end()) {
        return nullptr;
    }
    // Perform value matching, if requested
    if (!requestedClaim.values.empty()) {
        const std::vector<std::string>& values = requestedClaim.values;
        if (std::find(values.begin(), values.end(), ret->second.matchValue) == values.end()) {
            return nullptr;
        }
    }
    return &(ret->second);
}

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

            for (const auto &claim: match.claims) {
                if (credmanRuntimeVersion >= 2) {
                    ::AddFieldToEntrySet(entryId,
                                         strdup(claim->displayName.c_str()),
                                         strdup(claim->value.c_str()),
                                         setId,
                                         setIndex
                    );
                } else {
                    ::AddFieldForStringIdEntry(entryId,
                                               strdup(claim->displayName.c_str()),
                                               strdup(claim->value.c_str())
                    );
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

