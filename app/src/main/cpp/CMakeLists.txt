cmake_minimum_required(VERSION 3.22.1)
project(lla_android_bridge LANGUAGES CXX)

add_library(
    lla_android_bridge
    SHARED
    native_bridge.cpp
)

find_library(log-lib log)

target_compile_features(lla_android_bridge PRIVATE cxx_std_17)

target_link_libraries(
    lla_android_bridge
    ${log-lib}
)

# liblogicalaccess integration point:
# add the pinned dependency/submodule here and link the required
# core/reader/card targets to lla_android_bridge.
