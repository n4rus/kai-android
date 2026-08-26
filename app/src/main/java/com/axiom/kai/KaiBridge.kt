package com.axiom.kai

object KaiBridge {
    init {
        // Load Rust bridge first — it now also exposes JNI for KaiBridge (no need for kai_jni to link Rust)
        var ok = false
        try {
            System.loadLibrary("kai_bridge")
            android.util.Log.i("KaiBridge", "kai_bridge loaded OK")
            ok = true
        } catch (e: UnsatisfiedLinkError) { android.util.Log.w("KaiBridge", "kai_bridge not loaded: ${e.message}") }
        try {
            System.loadLibrary("kai_jni")
            android.util.Log.i("KaiBridge", "kai_jni loaded OK")
            ok = true
        } catch (e: UnsatisfiedLinkError) { android.util.Log.e("KaiBridge", "kai_jni not loaded: ${e.message}") }
        if (!ok) android.util.Log.w("KaiBridge", "No native libs loaded — using stub fallbacks")
        // Test call (now safe, will fallback to stub if JNI not found, thanks to Throwable catch in callers)
        try {
            val v = version()
            android.util.Log.i("KaiBridge", "version() = $v")
        } catch (t: Throwable) { android.util.Log.e("KaiBridge", "version() failed (stub): $t") }
    }
    external fun version(): String
    external fun calculateVFE(surprise: Float, kl: Float): Float
    external fun curvatureToTemp(baseTemp: Float, curvature: Float, alpha: Float): Float
    external fun loadGguf(path: String): Int
    external fun loadGgufSlot(slot: Int, path: String): Int
    external fun lastGgufInfo(): String
    external fun slotInfo(slot: Int): String
    external fun generate(prompt: String, temp: Float, vfe: Float): String
    external fun generateChat(historyJson: String, temp: Float, vfe: Float, slot: Int): String

    // Kotlin fallback when JNI not yet linked (gradle stub build)
    fun calculateVFEFallback(s: Float, k: Float) = s + k
    fun curvatureToTempFallback(t: Float, c: Float, a: Float) = t * (1 + a * c)
}
