package com.axiom.kai

object KaiBridge {
    init {
        try { System.loadLibrary("kai_jni") } catch (_: UnsatisfiedLinkError) {}
    }
    external fun version(): String
    external fun calculateVFE(surprise: Float, kl: Float): Float
    external fun curvatureToTemp(baseTemp: Float, curvature: Float, alpha: Float): Float
}
