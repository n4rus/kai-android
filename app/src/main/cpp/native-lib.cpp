#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "KaiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Stub JNI bridge — Day 3-4 will wire to Rust cdylib (kai_bridge)
// Rust side: #[no_mangle] pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32

extern "C" JNIEXPORT jstring JNICALL
Java_com_axiom_kai_KaiBridge_version(JNIEnv* env, jobject /* this */) {
    LOGI("KaiBridge.version() called");
    return env->NewStringUTF("kai-android 0.1.0 (stub) — wire Rust cdylib next");
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_axiom_kai_KaiBridge_calculateVFE(JNIEnv* env, jobject, jfloat surprise, jfloat kl) {
    // Mirrors physics-dialect calculate_vfe: VFE = surprise + KL
    return surprise + kl;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_axiom_kai_KaiBridge_curvatureToTemp(JNIEnv* env, jobject, jfloat baseTemp, jfloat curvature, jfloat alpha) {
    // T' = T * (1 + alpha * curvature)
    return baseTemp * (1.0f + alpha * curvature);
}
