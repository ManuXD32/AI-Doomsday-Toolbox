cmake_minimum_required(VERSION 3.22.1)
project("cpufeatures")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(cpufeatures SHARED CpuFeatures.cpp)

# Stable Audio 3 uses the LiteRT CompiledModel C ABI exported by the existing
# LiteRT-LM AAR. The worker resolves libLiteRt.so with dlopen/dlsym, so this
# target deliberately does not package a second LiteRT runtime.
add_library(
    stableaudio_worker
    SHARED
    stable_audio/LiteRtAbi.cpp
    stable_audio/StableAudio3Native.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(cpufeatures ${log-lib})
target_link_libraries(stableaudio_worker ${log-lib} ${android-lib} dl)
target_compile_features(stableaudio_worker PRIVATE cxx_std_17)
