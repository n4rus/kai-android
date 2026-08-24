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

# Rust cdylib — package only, don't link with absolute DT_NEEDED (breaks on device)
# Both libs are loaded via System.loadLibrary("kai_bridge") then "kai_jni" in Kotlin
# No target_link_libraries here — avoids absolute path in DT_NEEDED.
# The Rust so is still packaged via jniLibs (see gradle packaging) and via CMake's imported handling without linking.
# Instead, ensure the Rust so is copied to the build's jniLibs by Gradle (see app/build.gradle.kts packaging).
# No linking needed — JNI shim calls Rust C funcs via dlsym at runtime if needed, but for now we keep them separate and Kotlin will fallback to stub if not linked.
# To keep the previous behavior without absolute path, we set SONAME correctly if we do link:
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so)
    # Copy the prebuilt so to a staging dir that will be packaged, but don't add DT_NEEDED with absolute path
    configure_file(
        ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so
        ${CMAKE_CURRENT_BINARY_DIR}/libkai_bridge.so COPYONLY
    )
elseif(ANDROID_ABI STREQUAL "x86_64" AND EXISTS ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/x86_64-linux-android/release/libkai_bridge.so)
    configure_file(
        ${CMAKE_SOURCE_DIR}/../../../../rust-bridge/target/x86_64-linux-android/release/libkai_bridge.so
        ${CMAKE_CURRENT_BINARY_DIR}/libkai_bridge.so COPYONLY
    )
endif()
