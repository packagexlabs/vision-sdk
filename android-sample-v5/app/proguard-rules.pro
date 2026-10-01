# R8 rules for the release build (full mode). Library consumer rules already cover most of the
# dependency graph: VisionScanner (its public API, Gson, Retrofit, ONNX Runtime), the barcode engine
# com.packagexlabs:barcode-scanner (its JNI class com.example.barcodescanner.NativeDecoder and all of
# LiteRT), ARCore, ML Kit, CameraX, Hilt, DataStore and kotlinx.serialization all ship their own.
# What follows is what nothing else keeps.

# --- JNI: native code binds Java_<package>_<Class>_<method> symbols by name ---------------------------

# The sample's copies of the SDK's document kernels (io/packagex/visionsdk/native/DocumentNative.kt) and
# the SDK's own wrappers in the same package, all bound to libvision_native.so. The global
# `-keepclasseswithmembernames ... native <methods>` only keeps the names of classes that still have a
# native method after shrinking; keep the whole package so the objects and their loaders stay intact.
-keep class io.packagex.visionsdk.native.** { *; }

# docscanner-release.aar (local, unminified, ships no consumer rules): NativeBridge is bound to
# libdocscan.so and the rest is the tiny Kotlin API around it (quads/corners built from its float[]).
-keep class com.packagex.docscanner.** { *; }

# LiteRT (document dewarp): GpuDelegate/CompatibilityList are created via JNI and reflection from
# libtensorflowlite_gpu_jni.so; keep the GPU package and every native method name in the runtime.
# The barcode engine's own rules keep all of LiteRT too; these stay so the dewarp never depends on them.
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.** { native <methods>; }
# Optional nested type referenced by litert-gpu-api but absent from litert-gpu 1.4.2.
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend

# --- Crash reports ------------------------------------------------------------------------------------
# Readable stack traces from the release build (mapping.txt still maps the obfuscated names).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
