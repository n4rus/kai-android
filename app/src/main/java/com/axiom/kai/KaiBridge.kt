package com.axiom.kai

object KaiBridge {
    init {
        try { System.loadLibrary("kai_jni") } catch (e: UnsatisfiedLinkError) { /* stub fallback */ }
        try { System.loadLibrary("kai_bridge") } catch (_: UnsatisfiedLinkError) {}
    }
    external fun version(): String
    external fun calculateVFE(surprise: Float, kl: Float): Float
    external fun curvatureToTemp(baseTemp: Float, curvature: Float, alpha: Float): Float
    external fun loadGguf(path: String): Int
    external fun lastGgufInfo(): String
    external fun generate(prompt: String, temp: Float, vfe: Float): String

    // Kotlin fallback when JNI not yet linked (gradle stub build)
    fun calculateVFEFallback(s: Float, k: Float) = s + k
    fun curvatureToTempFallback(t: Float, c: Float, a: Float) = t * (1 + a * c)
}
