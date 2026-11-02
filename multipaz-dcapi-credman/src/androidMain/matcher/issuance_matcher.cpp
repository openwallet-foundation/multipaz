extern "C" {
#include "credentialmanager.h"
#include "cJSON.h"
}

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "cppbor.h"
#include "cppbor_parse.h"
#include "logger.h"

// Matcher for credential issuance, i.e. `navigator.credentials.create()` with OpenID4VCI.
//
// The registered data is a CBOR map with the entry to show to the user:
//
//   {
//     "title": tstr,
//     ? "subtitle": tstr,
//     ? "icon": bstr,       ; PNG
//   }
//
// The entry is shown if the request contains a credential offer for one of the
// supported protocols. Which credential to provision is decided by the application
// once the user has picked it.

static const char* kIssuanceProtocols[] = {
    "openid4vci",
    "openid4vci-v1",
    "openid4vci1.0",
    "openid4vci-1.0",
    "openid4vci-1.1",
};

static bool isIssuanceProtocol(const char* protocol) {
    if (protocol == nullptr) {
        return false;
    }
    for (auto p : kIssuanceProtocols) {
        if (strcmp(protocol, p) == 0) {
            return true;
        }
    }
    return false;
}

static bool hasCredentialOffer(cJSON* request) {
    if (!cJSON_IsObject(request)) {
        return false;
    }
    cJSON* protocol = cJSON_GetObjectItemCaseSensitive(request, "protocol");
    if (!isIssuanceProtocol(cJSON_GetStringValue(protocol))) {
        return false;
    }
    cJSON* data = cJSON_GetObjectItemCaseSensitive(request, "data");
    if (!cJSON_IsObject(data)) {
        return false;
    }
    cJSON* issuer = cJSON_GetObjectItemCaseSensitive(data, "credential_issuer");
    cJSON* configurationIds = cJSON_GetObjectItemCaseSensitive(data, "credential_configuration_ids");
    return cJSON_IsString(issuer) && cJSON_IsArray(configurationIds) && cJSON_GetArraySize(configurationIds) > 0;
}

extern "C" void issuance_matcher(void) {
    uint32_t dcRequestSize;
    ::GetRequestSize(&dcRequestSize);
    std::vector<char> dcRequestBlob(dcRequestSize + 1, 0);
    ::GetRequestBuffer(dcRequestBlob.data());
    cJSON* dcRequestJson = cJSON_Parse(dcRequestBlob.data());
    if (dcRequestJson == nullptr) {
        LOG("Error parsing request");
        return;
    }
    bool matched = false;
    cJSON* dcRequests = cJSON_GetObjectItemCaseSensitive(dcRequestJson, "requests");
    if (cJSON_IsArray(dcRequests)) {
        int numRequests = cJSON_GetArraySize(dcRequests);
        for (int n = 0; n < numRequests && !matched; n++) {
            matched = hasCredentialOffer(cJSON_GetArrayItem(dcRequests, n));
        }
    }
    cJSON_Delete(dcRequestJson);
    if (!matched) {
        return;
    }

    uint32_t optionsSize;
    ::GetCredentialsSize(&optionsSize);
    std::vector<uint8_t> options(optionsSize);
    ::ReadCredentialsBuffer(options.data(), 0, optionsSize);
    auto [item, pos, message] = cppbor::parse(options.data(), options.size());
    if (item == nullptr || item->asMap() == nullptr) {
        LOG("Error parsing creation options: %s", message.c_str());
        return;
    }
    auto map = item->asMap();
    const auto& titleItem = map->get("title");
    if (titleItem == nullptr || titleItem->asTstr() == nullptr) {
        LOG("No title in creation options");
        return;
    }
    std::string title = titleItem->asTstr()->value();
    std::string subtitle;
    const auto& subtitleItem = map->get("subtitle");
    if (subtitleItem != nullptr && subtitleItem->asTstr() != nullptr) {
        subtitle = subtitleItem->asTstr()->value();
    }
    std::vector<uint8_t> icon;
    const auto& iconItem = map->get("icon");
    if (iconItem != nullptr && iconItem->asBstr() != nullptr) {
        icon = iconItem->asBstr()->value();
    }

    ::AddStringIdEntry(
        (char*) "openid4vci",
        icon.empty() ? nullptr : (char*) icon.data(),
        icon.size(),
        (char*) title.c_str(),
        subtitle.empty() ? nullptr : (char*) subtitle.c_str(),
        nullptr,
        nullptr);
}
