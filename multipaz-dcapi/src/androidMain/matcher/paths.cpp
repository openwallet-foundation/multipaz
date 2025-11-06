
#include "paths.h"

static void generate(
        int index,
        std::vector<int>& currentPath,
        std::vector<std::vector<int>>& allPaths,
        const std::vector<int>& maxPath) {
    if (index == maxPath.size()) {
        allPaths.push_back(currentPath); // Add a copy of the completed path.
        return;
    }
    for (int value = 0; value < maxPath[index]; ++value) {
        currentPath[index] = value;
        generate(index + 1, currentPath, allPaths, maxPath);
    }
}

std::vector<std::vector<int>> generateAllPaths(const std::vector<int>& maxPath) {
    if (maxPath.empty()) {
        return {{}};
    }

    std::vector<std::vector<int>> allPaths;
    std::vector<int> currentPath(maxPath.size(), 0);
    generate(0, currentPath, allPaths, maxPath);
    return allPaths;
}
