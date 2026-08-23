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

list(PREPEND CMAKE_PREFIX_PATH "${LLA_CONAN_DIR}")
find_package(logicalaccess CONFIG REQUIRED)

add_library(
    lla_android_bridge
    SHARED
    native_bridge.cpp
)

target_compile_features(lla_android_bridge PRIVATE cxx_std_17)

target_link_libraries(
    lla_android_bridge
    PRIVATE
    logicalaccess::logicalaccess
    log
)
