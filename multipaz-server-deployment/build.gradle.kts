// multipaz-server-deployment/build.gradle.kts
// Tasks for building Docker images and deployment bundles

tasks.register("buildAllFatJars") {
    description = "Build fat JARs for all server modules"
    group = "multipaz-server-deployment"

    dependsOn(
        ":multipaz-verifier-server:buildFatJar",
        ":multipaz-openid4vci-server:buildFatJar",
        ":multipaz-backend-server:buildFatJar",
        ":multipaz-records-server:buildFatJar",
        ":multipaz-csa-server:buildFatJar"
    )
}

tasks.register("buildWebFrontend") {
    description = "Build the web frontend (Kotlin/JS)"
    group = "multipaz-server-deployment"

    dependsOn(":multipaz-server-frontend:jsBrowserDistribution")
}

tasks.register("buildAll") {
    description = "Build all server JARs and web frontend"
    group = "multipaz-server-deployment"

    dependsOn("buildAllFatJars", "buildWebFrontend")
}

// Helper function to get container tool (podman or docker)
fun getContainerTool(): String {
    return if (file("/usr/bin/podman").exists() ||
        file("/usr/local/bin/podman").exists() ||
        file("/opt/homebrew/bin/podman").exists()) {
        "podman"
    } else {
        "docker"
    }
}

tasks.register<Exec>("buildDockerImage") {
    description = "Build Docker image for native architecture"
    group = "multipaz-server-deployment"

    dependsOn("buildAll")

    workingDir = rootProject.projectDir

    val containerTool = getContainerTool()
    val version = rootProject.extra["projectVersionName"] as String

    // Detect native architecture
    val arch = System.getProperty("os.arch")
    val platform = when {
        arch == "aarch64" || arch == "arm64" -> "linux/arm64"
        else -> "linux/amd64"
    }

    commandLine(
        containerTool, "build",
        "--platform", platform,
        "-f", "multipaz-server-deployment/docker/Dockerfile",
        "-t", "multipaz/server-bundle:${version}",
        "-t", "multipaz/server-bundle:latest",
        "."
    )
}

tasks.register<Exec>("buildDockerImageAmd64") {
    description = "Build Docker image for amd64 (x86_64) architecture"
    group = "multipaz-server-deployment"

    dependsOn("buildAll")

    workingDir = rootProject.projectDir

    val containerTool = getContainerTool()
    val version = rootProject.extra["projectVersionName"] as String

    commandLine(
        containerTool, "build",
        "--platform", "linux/amd64",
        "-f", "multipaz-server-deployment/docker/Dockerfile",
        "-t", "multipaz/server-bundle:${version}-amd64",
        "-t", "multipaz/server-bundle:latest-amd64",
        "."
    )
}

tasks.register<Exec>("buildDockerImageArm64") {
    description = "Build Docker image for arm64 (Apple Silicon, AWS Graviton) architecture"
    group = "multipaz-server-deployment"

    dependsOn("buildAll")

    workingDir = rootProject.projectDir

    val containerTool = getContainerTool()
    val version = rootProject.extra["projectVersionName"] as String

    commandLine(
        containerTool, "build",
        "--platform", "linux/arm64",
        "-f", "multipaz-server-deployment/docker/Dockerfile",
        "-t", "multipaz/server-bundle:${version}-arm64",
        "-t", "multipaz/server-bundle:latest-arm64",
        "."
    )
}

tasks.register<Exec>("runDockerImage") {
    description = "Run the Docker image locally"
    group = "multipaz-server-deployment"

    val containerTool = getContainerTool()

    commandLine(
        containerTool, "run",
        "--rm",
        "-p", "8000-8008:8000-8008",
        "multipaz/server-bundle:latest"
    )
}
