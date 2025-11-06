
#include "jni.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <format>

#include "../src/androidMain/matcher/CredentialDatabase.h"
#include "../src/androidMain/matcher/Request.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_org_multipaz_presentment_model_MatcherDcqlQueryExecuteTest_executeDcqlQuery(
        JNIEnv *env,
        jobject thiz,
        jbyteArray requestBytes,
        jbyteArray credentialDatabase) {
    jboolean isCopy;
    const char* requestBuf = (const char*) env->GetByteArrayElements(requestBytes, &isCopy);
    size_t requestSize = env->GetArrayLength(requestBytes);
    const uint8_t* credentialDatabaseBuf = (uint8_t*) env->GetByteArrayElements(credentialDatabase, &isCopy);
    size_t credentialDatabaseSize = env->GetArrayLength(credentialDatabase);

    CredentialDatabase* db = new CredentialDatabase(credentialDatabaseBuf, credentialDatabaseSize);

    cJSON* requestJson = cJSON_Parse(requestBuf);
    cJSON *requests = cJSON_GetObjectItemCaseSensitive(requestJson, "requests");
    cJSON *request = cJSON_GetArrayItem(requests, 0);
    cJSON *protocol = cJSON_GetObjectItem(request, "protocol");
    std::string protocolValue = std::string(cJSON_GetStringValue(protocol));
    cJSON *protocolData = cJSON_GetObjectItem(request, "data");
    auto openid4vpRequest = OpenID4VPRequest::parseOpenID4VP(protocolData, protocolValue);
    auto dcqlResponse = openid4vpRequest->dclqQuery.execute(db);

    std::string output;
    output.append("DcqlResponse\n");
    for (auto const& credentialSet : dcqlResponse->credentialSets) {
        output.append("  CredentialSet\n");
        output.append("    optional " + std::string(credentialSet.optional ? "true" : "false") + "\n");
        output.append("    options\n");
        for (auto const& option : credentialSet.options) {
            output.append("      option\n");
            output.append("        members\n");
            for (auto const& member : option.members) {
                output.append("          member\n");
                output.append("            matches\n");
                for (auto const& match : member.matches) {
                    output.append("              match\n");
                    output.append("                credential " + match.credential->title + "\n");
                    output.append("                claims\n");
                    for (auto const &claim: match.claims) {
                        output.append("                  claim\n");
                        output.append("                    claimName " + claim->claimName + "\n");
                        output.append("                    displayName " + claim->displayName + "\n");
                        output.append("                    value " + claim->value + "\n");
                    }
                }
            }
        }
    }

    return env->NewStringUTF(output.c_str());
}
