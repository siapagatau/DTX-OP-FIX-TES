cmake_minimum_required(VERSION 3.22.1)
project("padengine")

find_package(oboe REQUIRED CONFIG)

add_library(padengine SHARED
    native-lib.cpp
    PadEngine.cpp
    AudioBackends.cpp
)

target_link_libraries(padengine
    oboe::oboe
    aaudio
    OpenSLES
    log
    android
)

# 16 KB page size (syarat Google Play): NDK r28+ sudah default, tapi flag ini
# dibiarkan eksplisit supaya tetap benar kalau NDK-nya diturunkan ke r27 atau lebih lama.
target_link_options(padengine PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
