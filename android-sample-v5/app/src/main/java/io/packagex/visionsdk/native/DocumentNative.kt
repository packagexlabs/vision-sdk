package io.packagex.visionsdk.native

import android.util.Log

// The NEON document kernels ship in VisionScanner v2.7.0's libvision_native.so, but their Kotlin wrappers
// (vision-sdk-android VisionScanner/.../native/DocumentResampleNative.kt, DocumentEnhanceNative.kt) were added
// after that release. These are those wrappers; the JNI names fix the package. Drop this file once the
// SDK ships them (the build then fails with a duplicate class).

private val loaded: Boolean by lazy {
    runCatching { System.loadLibrary("vision_native") }.onFailure { Log.w("DocumentNative", "vision_native unavailable", it) }.isSuccess
}

/** UVDoc backward-map resample (NEON). False when the native library is unavailable. */
object DocumentResampleNative {
    fun resample(src: IntArray, width: Int, height: Int, gridX: FloatArray, gridY: FloatArray, gridWidth: Int, gridHeight: Int, dst: IntArray): Boolean {
        if (!loaded) return false
        return runCatching { resampleArgbNative(src, width, height, gridX, gridY, gridWidth, gridHeight, dst) }
            .getOrElse { Log.e("DocumentResample", "Native resample failed", it); false }
    }

    @JvmStatic
    private external fun resampleArgbNative(src: IntArray, width: Int, height: Int, gridX: FloatArray, gridY: FloatArray, gridWidth: Int, gridHeight: Int, dst: IntArray): Boolean
}

/** Document clean-up in place (NEON). False when the native library is unavailable. */
object DocumentEnhanceNative {
    fun enhanceInPlace(pixels: IntArray, width: Int, height: Int): Boolean {
        if (!loaded || pixels.size < width * height) return false
        return runCatching { enhanceArgbNative(pixels, width, height); true }
            .getOrElse { Log.e("DocumentEnhance", "Native enhance failed", it); false }
    }

    @JvmStatic
    private external fun enhanceArgbNative(pixels: IntArray, width: Int, height: Int)
}
