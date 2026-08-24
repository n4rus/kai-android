cmake_minimum_required(VERSION 3.22.1)
project(kai_jni)

# Rust cdylib built via cargo-ndk -> target/aarch64-linux-android/release/libkai_bridge.so
# We link it as imported prebuilt; CMake just builds the JNI shim.

add_library(kai_jni SHARED native-lib.cpp)

# Headers for JNI (NDK provides jni.h)
find_library(log-lib log)

target_link_libraries(kai_jni
    ${log-lib}
    android
)

# Rust cdylib — auto-link if built via cargo-ndk
# The .so is at rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so
# If not present, JNI still builds (Kotlin fallbacks to stub)
if(EXISTS ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so)
    add_library(kai_bridge SHARED IMPORTED)
    set_target_properties(kai_bridge PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so)
    target_link_libraries(kai_jni kai_bridge)
endif()
