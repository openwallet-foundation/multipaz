#ifndef CREDENTIALMANAGER_H
#define CREDENTIALMANAGER_H

#include <stdint.h>
#include <stdlib.h>

// Deprecated. Use AddStringIdEntry instead.
#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddEntry")))
#endif
void AddEntry(long long cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning);

// Deprecated. Use AddFieldForStringIdEntry instead.
#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddField")))
#endif
void AddField(long long cred_id, char *field_display_name, char *field_display_value);

#if defined(__wasm__)
__attribute__((import_module("credman_v2"), import_name("AddEntrySet")))
#endif
void AddEntrySet(char *set_id, int set_length);

#if defined(__wasm__)
__attribute__((import_module("credman_v2"), import_name("AddEntryToSet")))
#endif
void AddEntryToSet(char *cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning, char *metadata, char *set_id, int set_index);

#if defined(__wasm__)
__attribute__((import_module("credman_v2"), import_name("AddFieldToEntrySet")))
#endif
void AddFieldToEntrySet(char *cred_id, char *field_display_name, char *field_display_value, char *set_id, int set_index);

#if defined(__wasm__)
__attribute__((import_module("credman_v2"), import_name("AddPaymentEntryToSet")))
#endif
void AddPaymentEntryToSet(char *cred_id, char *merchant_name, char *payment_method_name, char *payment_method_subtitle, char* payment_method_icon, size_t payment_method_icon_len, char *transaction_amount, char* bank_icon, size_t bank_icon_len, char* payment_provider_icon, size_t payment_provider_icon_len, char *metadata, char *set_id, int set_index);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddStringIdEntry")))
#endif
void AddStringIdEntry(char *cred_id, char* icon, size_t icon_len, char *title, char *subtitle, char *disclaimer, char *warning);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddFieldForStringIdEntry")))
#endif
void AddFieldForStringIdEntry(char *cred_id, char *field_display_name, char *field_display_value);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("GetRequestBuffer")))
#endif
void GetRequestBuffer(void* buffer);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("GetRequestSize")))
#endif
void GetRequestSize(uint32_t* size);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("ReadCredentialsBuffer")))
#endif
size_t ReadCredentialsBuffer(void* buffer, size_t offset, size_t len);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("GetCredentialsSize")))
#endif
void GetCredentialsSize(uint32_t* size);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("GetWasmVersion")))
#endif
void GetWasmVersion(uint32_t* version);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddPaymentEntry")))
#endif
void AddPaymentEntry(char *cred_id, char *merchant_name, char *payment_method_name, char *payment_method_subtitle, char* payment_method_icon, size_t payment_method_icon_len, char *transaction_amount, char* bank_icon, size_t bank_icon_len, char* payment_provider_icon, size_t payment_provider_icon_len);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("AddInlineIssuanceEntry")))
#endif
void AddInlineIssuanceEntry(char *cred_id, char* icon, size_t icon_len, char *title, char *subtitle);

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("SetAdditionalDisclaimerAndUrlForVerificationEntry")))
#endif
void SetAdditionalDisclaimerAndUrlForVerificationEntry(char *cred_id, char *secondary_disclaimer, char *url_display_text, char *url_value);

typedef struct CallingAppInfo {
    char package_name[256];
    char origin[512];
} CallingAppInfo;

#if defined(__wasm__)
__attribute__((import_module("credman"), import_name("GetCallingAppInfo")))
#endif
void GetCallingAppInfo(CallingAppInfo* info);

#endif