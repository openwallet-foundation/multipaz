
#include <string>
#include <vector>

#include "logger.h"

std::string joinStrings(const std::vector<std::string>& vec, const std::string& separator) {
    if (vec.empty()) {
        return "";
    }

    std::string result = vec[0];
    for (size_t i = 1; i < vec.size(); ++i) {
        result += separator;
        result += vec[i];
    }
    return result;
}
