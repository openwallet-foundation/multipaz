plugins {
    alias(libs.plugins.androidLibrary)
}


android {
    namespace = "org.multipaz.presentment.model"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = 26
    }

    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }
}

