#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "KaiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Rust cdylib — declared in rust-bridge/src/lib.rs
extern "C" {
    int kai_load_gguf(const char* path);
    float kai_calculate_vfe(float surprise, float kl);
    float kai_curvature_to_temp(float baseTemp, float curvature, float alpha);
    const char* kai_version();
    char* kai_generate(const char* prompt, float temp, float vfe);
    void kai_free_string(char* s);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axiom_kai_KaiBridge_version(JNIEnv* env, jobject) {
    const char* v = kai_version();
    return env->NewStringUTF(v ? v : "kai-bridge 0.2.0");
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_axiom_kai_KaiBridge_calculateVFE(JNIEnv*, jobject, jfloat surprise, jfloat kl) {
    return kai_calculate_vfe(surprise, kl);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_axiom_kai_KaiBridge_curvatureToTemp(JNIEnv*, jobject, jfloat baseTemp, jfloat curvature, jfloat alpha) {
    return kai_curvature_to_temp(baseTemp, curvature, alpha);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_axiom_kai_KaiBridge_loadGguf(JNIEnv* env, jobject, jstring path) {
    if (!path) return -1;
    const char* c = env->GetStringUTFChars(path, nullptr);
    int r = kai_load_gguf(c);
    env->ReleaseStringUTFChars(path, c);
    LOGI("kai_load_gguf -> %d", r);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axiom_kai_KaiBridge_generate(JNIEnv* env, jobject, jstring prompt, jfloat temp, jfloat vfe) {
    if (!prompt) return env->NewStringUTF("[Kai] empty prompt");
    const char* c = env->GetStringUTFChars(prompt, nullptr);
    char* out = kai_generate(c, temp, vfe);
    env->ReleaseStringUTFChars(prompt, c);
    if (!out) return env->NewStringUTF("[Kai] generate error");
    jstring j = env->NewStringUTF(out);
    kai_free_string(out);
    return j;
}
