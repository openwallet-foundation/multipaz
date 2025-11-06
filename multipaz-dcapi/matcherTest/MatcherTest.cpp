
#include "jni.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <format>

// See matcher.cpp for this
extern "C" void matcher(void);

static void* requestBuf = nullptr;
static uint32_t requestSize = 0;

static void* credentialDatabaseBuf = nullptr;
static uint32_t credentialDatabaseSize = 0;

static std::string output;

extern "C"
JNIEXPORT jstring JNICALL
Java_org_multipaz_presentment_model_MatcherTest_runMatcher(
        JNIEnv *env,
        jobject thiz,
        jbyteArray request,
        jbyteArray credentialDatabase) {
    jboolean isCopy;
    requestBuf = env->GetByteArrayElements(request, &isCopy);
    requestSize = env->GetArrayLength(request);
    credentialDatabaseBuf = env->GetByteArrayElements(credentialDatabase, &isCopy);
    credentialDatabaseSize = env->GetArrayLength(credentialDatabase);
    output = "";
    matcher();
    return env->NewStringUTF(output.c_str());
}

// Stub out all the CredentialManager functions so they pipe/to from our tests

// Deprecated. Use AddStringIdEntry instead.
extern "C" void AddEntry(long long cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning) {
}

// Deprecated. Use AddFieldForStringIdEntry instead.
extern "C" void AddField(long long cred_id, char *field_display_name, char *field_display_value) {

}

extern "C" void AddStringIdEntry(char *cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning) {
    output += "Entry\n";
    output += std::format("  cred_id {}\n", cred_id);
}

extern "C" void AddFieldForStringIdEntry(char *cred_id, char *field_display_name, char *field_display_value) {
    if (field_display_value != nullptr) {
        output += std::format("  {}: {}\n", field_display_name, field_display_value);
    } else {
        output += std::format("  {}\n", field_display_name);
    }
}

extern "C" void GetRequestBuffer(void* buffer) {
    memcpy(buffer, requestBuf, requestSize);
}

extern "C" void GetRequestSize(uint32_t* size) {
    *size = requestSize;
}

extern "C" size_t ReadCredentialsBuffer(void* buffer, size_t offset, size_t len) {
    memcpy(buffer, ((char*) credentialDatabaseBuf) + offset, len);
    return len;
}

extern "C" void GetCredentialsSize(uint32_t* size) {
    *size = credentialDatabaseSize;
}

extern "C" void AddPaymentEntry(char *cred_id, char *merchant_name, char *payment_method_name, char *payment_method_subtitle, char* payment_method_icon, size_t payment_method_icon_len, char *transaction_amount, char* bank_icon, size_t bank_icon_len, char* payment_provider_icon, size_t payment_provider_icon_len) {
}

typedef struct CallingAppInfo {
    char package_name[256];
    char origin[512];
} CallingAppInfo;

extern "C" void GetCallingAppInfo(CallingAppInfo* info) {
    // TODO
}

extern "C" void GetWasmVersion(uint32_t* version) {
    *version = 2;
}

extern "C" void AddEntrySet(char *set_id, int set_length) {
    output += "Set\n";
    output += std::format("  set_id {}\n", set_id);
}

extern "C" void AddEntryToSet(char *cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning, char *metadata, char *set_id, int set_index) {
    output += std::format("  SetEntry set_index {}\n", set_index);
    output += std::format("    cred_id {}\n", cred_id);
}

extern "C" void AddFieldToEntrySet(char *cred_id, char *field_display_name, char *field_display_value, char *set_id, int set_index) {
    if (field_display_value != nullptr) {
        output += std::format("    {}: {}\n", field_display_name, field_display_value);
    } else {
        output += std::format("    {}\n", field_display_name);
    }
}
