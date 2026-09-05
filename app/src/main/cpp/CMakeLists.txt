cmake_minimum_required(VERSION 3.22.1)
project(lla_android_bridge LANGUAGES CXX)

if(NOT DEFINED LLA_CONAN_DIR)
    set(LLA_CONAN_DIR "${CMAKE_SOURCE_DIR}/../../../../../.tools/conan/android-arm64")
endif()

if(NOT EXISTS "${LLA_CONAN_DIR}")
    message(FATAL_ERROR
        "liblogicalaccess Conan files not found at ${LLA_CONAN_DIR}. "
        "Run build-and-deploy.bat to prepare the Android native dependencies.")
endif()

# Android NDK toolchain sets CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY, which
# blocks CMAKE_PREFIX_PATH from being searched outside the sysroot.  BOTH
# keeps the NDK sysroot search while also honoring CMAKE_PREFIX_PATH for our
# Conan packages.  This applies to transitive find_dependency() calls too.
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
list(PREPEND CMAKE_PREFIX_PATH "${LLA_CONAN_DIR}")
find_package(logicalaccess CONFIG REQUIRED)

add_library(
    lla_android_bridge
    SHARED
    native_bridge.cpp
)

target_compile_features(lla_android_bridge PRIVATE cxx_std_17)

# Required for Android 15+ 16 KB page size compatibility.
target_link_options(lla_android_bridge PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(
    lla_android_bridge
    PRIVATE
    logicalaccess::logicalaccess
    Boost::boost
    log
)
