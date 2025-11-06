
extern "C" {
#include "credentialmanager.h"
#include "cJSON.h"
}

#include <set>
#include <algorithm>

#include "CredentialDatabase.h"
#include "Request.h"
#include "logger.h"

extern "C" void matcher(void) {
    CallingAppInfo* appInfo = (CallingAppInfo*) malloc(sizeof(CallingAppInfo));
    ::GetCallingAppInfo(appInfo);

    uint32_t credsBlobSize;
    ::GetCredentialsSize(&credsBlobSize);
    uint8_t* credsBlob = (uint8_t*) malloc(credsBlobSize);
    ::ReadCredentialsBuffer((void*) credsBlob, 0, credsBlobSize);

    CredentialDatabase* db = new CredentialDatabase(credsBlob, credsBlobSize);

    uint32_t dcRequestSize;
    ::GetRequestSize(&dcRequestSize);
    char* dcRequestBlob = (char*) malloc(dcRequestSize);
    ::GetRequestBuffer(dcRequestBlob);
    cJSON* dcRequestJson = cJSON_Parse(dcRequestBlob);
    cJSON *dcRequests = cJSON_GetObjectItemCaseSensitive(dcRequestJson, "requests");
    if (cJSON_IsArray(dcRequests)) {
        int numRequests = cJSON_GetArraySize(dcRequests);
        for (int n = 0; n < numRequests; n++) {
            cJSON *dcRequest = cJSON_GetArrayItem(dcRequests, n);
            if (!cJSON_IsObject(dcRequest)) {
                continue;
            }
            cJSON *protocol = cJSON_GetObjectItem(dcRequest, "protocol");
            std::string protocolValue = std::string(cJSON_GetStringValue(protocol));
            cJSON *protocolData = cJSON_GetObjectItem(dcRequest, "data");

            if (protocolValue == "openid4vp" ||
                protocolValue == "openid4vp-v1-unsigned" ||
                protocolValue == "openid4vp-v1-signed") {

                auto request = OpenID4VPRequest::parseOpenID4VP(protocolData, protocolValue);
                auto dcqlResponse = request->dclqQuery.execute(db);
                if (dcqlResponse.has_value()) {
                    auto combinations = dcqlResponse.value().getCredentialCombinations();
                    for (auto const &combination: combinations) {
                        combination.addToCredmanPicker(*request);
                    }
                }
                break;

            } else if (protocolValue == "org.iso.mdoc" || protocolValue == "org-iso-mdoc") {
                auto request = MdocRequest::parseMdocApi(protocolValue, protocolData);
                auto combinations = request->getCredentialCombinations(db);
                for (auto const& combination : combinations) {
                    combination.addToCredmanPicker(*request);
                }
                break;
            }
        }
    }
}
