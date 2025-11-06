
#pragma once

#ifdef MATCHER_TEST_BUILD

#include <android/log.h>
#define  LOG(...)  __android_log_print(ANDROID_LOG_DEBUG, "MatcherTest", __VA_ARGS__)

#else

#define  LOG(...)

#endif


std::string joinStrings(const std::vector<std::string>& vec, const std::string& separator = ", ");
