#include <jni.h>
#include <android/log.h>

#define LOG_TAG "KaiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Minimal kai_jni — Rust kai_bridge now provides all KaiBridge JNI directly
// This lib is kept for legacy load order, but does not define KaiBridge JNI (to avoid duplicate with Rust)
// If Rust is not yet built, Kotlin fallbacks to stub

extern "C" JNIEXPORT jstring JNICALL
Java_com_axiom_kai_KaiBridge_versionStub(JNIEnv* env, jobject) {
    return env->NewStringUTF("kai-jni 0.1.0 stub (Rust provides real JNI)");
}
