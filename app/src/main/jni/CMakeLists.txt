cmake_minimum_required(VERSION 3.22)

project("joybridgeadb")

set(CMAKE_CXX_STANDARD 17)

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

# Implements the SPAKE2 password-authenticated key exchange used by
# Android's wireless-debugging pairing protocol. Adapted directly from
# Shizuku's own adb_pairing.cpp (github.com/RikkaApps/Shizuku, GPL-3.0),
# which is the only reason this protocol - otherwise undocumented and
# native-only - is replicable here at all.
add_library(adb SHARED
        adb_pairing.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)
