plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val llaConanDir = rootProject.file(".tools/conan/android-arm64").invariantSeparatorsPath

android {
    namespace = "de.shansen.liblogicalaccessnfc"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "de.shansen.liblogicalaccessnfc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf(
                    "-DLLA_CONAN_DIR=$llaConanDir",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDir(rootProject.file(".tools/jniLibs"))

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core-project"))
    implementation(project(":rfidgear-runtime"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}
