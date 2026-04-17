// multipaz-server-deployment/build.gradle.kts
// Tasks for building Docker images and deployment bundles

val serverProjects = listOf(
    "multipaz-verifier-server",
    "multipaz-openid4vci-server",
    "multipaz-backend-server",
    "multipaz-records-server",
    "multipaz-csa-server",
    "multipaz-upay-server",
)

tasks.register("collectDependencies") {
    description = "Collect thin server JARs and shared dependency JARs into a staging directory"
    group = "multipaz-server-deployment"

    // Depend on jar tasks for server projects plus their full runtime classpath build dependencies
    for (name in serverProjects) {
        val serverProject = project(":${name}")
        dependsOn("${serverProject.path}:jar")
        dependsOn(serverProject.configurations.getByName("runtimeClasspath").buildDependencies)
    }

    val stagingDir = layout.buildDirectory.dir("docker-staging")

    outputs.dir(stagingDir)

    doLast {
        val jarsDir = stagingDir.get().dir("jars").asFile
        val libsDir = stagingDir.get().dir("libs").asFile
        jarsDir.mkdirs()
        libsDir.mkdirs()

        // Collect all unique dependency JARs from all server projects
        val seenLibs = mutableSetOf<String>()
        for (name in serverProjects) {
            val serverProject = project(":${name}")
            val runtimeCp = serverProject.configurations.getByName("runtimeClasspath")

            // Copy the thin server JAR
            val shortName = name.removePrefix("multipaz-").removeSuffix("-server")
            val jarFile = serverProject.tasks.getByName<Jar>("jar").archiveFile.get().asFile
            jarFile.copyTo(File(jarsDir, "${shortName}.jar"), overwrite = true)

            // Copy dependency JARs (deduplicated by filename)
            for (dep in runtimeCp.resolve()) {
                if (dep.name.endsWith(".jar") && seenLibs.add(dep.name)) {
                    dep.copyTo(File(libsDir, dep.name), overwrite = true)
                }
            }
        }

        val libCount = libsDir.listFiles()?.size ?: 0
        println("Collected ${serverProjects.size} server JARs and ${libCount} shared dependency JARs")
    }
}

tasks.register("buildWebFrontend") {
    description = "Build the web frontend (Kotlin/JS)"
    group = "multipaz-server-deployment"

    dependsOn(":multipaz-server-frontend:jsBrowserDistribution")
}

tasks.register("buildAll") {
    description = "Build all server JARs and web frontend"
    group = "multipaz-server-deployment"

    dependsOn("collectDependencies", "buildWebFrontend")
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
        "-p", "8000-8009:8000-8009",
        "multipaz/server-bundle:latest"
    )
}
