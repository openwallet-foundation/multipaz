plugins {
    alias(libs.plugins.androidLibrary)
}


android {
    namespace = "org.multipaz.presentment.model"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
    }

    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
}

