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
                    "-DANDROID_STL=c++_shared",
                    // Conan packages were built as Release; CMakeDeps wraps all include dirs and
                    // link flags in $<$<CONFIG:Release>:...> generator expressions, which evaluate
                    // to empty for Debug. Force Release so the Conan targets resolve correctly.
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    implementation(project(":core-usecase"))
    implementation(project(":rfidgear-runtime"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}
