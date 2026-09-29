package io.packagex.visiondemo.document

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.packagex.visiondemo.BuildConfig
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite wrapper around UVDoc (tanguymagne/UVDoc, MIT), the document unwarping
 * network from "UVDoc: Neural Grid-based Document Unwarping" (SIGGRAPH Asia
 * 2023). It takes a fixed 488 × 712 view of the page and predicts a coarse grid
 * of normalised sampling coordinates — see [BackwardMap].
 *
 * fp16 weights, float32 I/O, run on the LiteRT runtime bundled in the app (not Play Services TFLite: GMS
 * on some devices, the Datalogic Memor 35 among them, has no `tflite_gpu_dynamite` module, so its GPU
 * delegate is never available there). GPU delegate first (see [gpuDelegate]), multi-threaded XNNPACK on
 * the CPU when the GPU can't take the graph. Exported by scripts/export_uvdoc_tflite.py from the checkpoint the
 * iOS demo ships as UVDoc.mlmodelc. Loading is slow (GPU kernel compile on a first run): construct it off
 * the main thread, ahead of the first page ([DocumentSession.warm]).
 */
class DocumentDewarpModel internal constructor(
    context: Context,
    /** False forces the CPU path (benchmarks). */
    preferGpu: Boolean,
    /** Whether a freshly built GPU interpreter may be kept; tests replace it to force the fallback. */
    gpuCheck: (Interpreter) -> Boolean,
) : AutoCloseable {
    constructor(context: Context, preferGpu: Boolean = true) : this(context, preferGpu, { it.passesSelfCheck() })

    companion object {
        /** The input size UVDoc was trained at; not negotiable. */
        const val INPUT_WIDTH = 488
        const val INPUT_HEIGHT = 712
        private const val ASSET = "uvdoc_fp16.tflite"
        private const val TAG = "DocumentDewarp"
    }

    /** True when the interpreter runs on the GPU delegate (fp16), false for CPU/XNNPACK. */
    var usingGpu = false
        private set

    /** Owned here: an interpreter only borrows its delegates. */
    private var gpuDelegate: GpuDelegate? = null

    private val interpreter: Interpreter? =
        try {
            val model =
                context.assets.open(ASSET).use { it.readBytes() }.let { bytes ->
                    ByteBuffer
                        .allocateDirect(bytes.size)
                        .order(ByteOrder.nativeOrder())
                        .put(bytes)
                        .apply { rewind() }
                }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            fun cpu() = Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
            val gpu = if (preferGpu) gpuDelegate(context) else null
            if (gpu == null) {
                cpu()
            } else {
                val onGpu = try {
                    Interpreter(model, Interpreter.Options().addDelegate(gpu))
                } catch (e: Exception) {
                    // Driver rejected the graph: drop to CPU rather than fail the page.
                    Log.w(TAG, "GPU delegate rejected UVDoc; using CPU", e)
                    null
                }
                // The GPU may be off LiteRT's compatibility list (see gpuDelegate), so a graph it accepted still has to
                // produce a usable grid before any page relies on it.
                if (onGpu != null && gpuCheck(onGpu)) {
                    onGpu.also { gpuDelegate = gpu; usingGpu = true }
                } else {
                    if (onGpu != null) Log.w(TAG, "UVDoc on the GPU failed its self-check; using CPU")
                    onGpu?.close()
                    gpu.close()
                    cpu()
                }
            }.also { if (BuildConfig.DEBUG) Log.d(TAG, "UVDoc ready on ${if (usingGpu) "GPU" else "CPU (XNNPACK, $threads threads)"}") }
        } catch (e: Exception) {
            Log.e(TAG, "UVDoc unavailable", e)
            null
        }

    /**
     * The GPU delegate: with this device's best options where [CompatibilityList] lists the GPU, and with the
     * defaults where it doesn't. The list is conservative: it leaves out the Memor 35's Adreno 613, which runs
     * UVDoc correctly and ~40 % faster than the CPU (and runs docscanner's corner model through the same
     * delegate). A GPU whose driver rejects the graph fails [Interpreter] creation, which falls back to CPU.
     */
    private fun gpuDelegate(context: Context): GpuDelegate? = try {
        CompatibilityList().use { compat ->
            val listed = compat.isDelegateSupportedOnThisDevice
            if (BuildConfig.DEBUG && !listed) Log.d(TAG, "GPU not in LiteRT's compatibility list; trying the delegate anyway")
            // Serialized kernels make every open after the first fast.
            val options: GpuDelegateFactory.Options = (if (listed) compat.bestOptionsForThisDevice else GpuDelegateFactory.Options())
                .setPrecisionLossAllowed(true)
                .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
                .setSerializationParams(File(context.cacheDir, "uvdoc_gpu").apply { mkdirs() }.absolutePath, "uvdoc-fp16-litert-v1")
            GpuDelegate(options)
        }
    } catch (e: Exception) {
        Log.w(TAG, "GPU delegate unavailable", e)
        null
    }

    val isAvailable: Boolean get() = interpreter != null

    /**
     * Predicts the backward map for a page, or null when the model is missing, fails, or predicts a non-finite grid
     * ([DocumentDewarp.dewarp] then leaves the page as it is).
     * Synchronized: one interpreter, and TFLite's run() is not thread-safe —
     * two concurrent pages segfaulted inside the runtime.
     */
    @Synchronized
    fun backwardMap(page: Bitmap): BackwardMap? {
        val interpreter = interpreter ?: return null
        val scaled = Bitmap.createScaledBitmap(page, INPUT_WIDTH, INPUT_HEIGHT, true)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        if (scaled !== page) scaled.recycle()
        return try {
            val map = interpreter.backwardMap(pixels)
            // A NaN/Inf grid would resample garbage: the page passes through un-dewarped instead.
            if (map != null && !map.isFinite) null.also { Log.w(TAG, "UVDoc produced a non-finite grid; page left as is") } else map
        } catch (e: Exception) {
            Log.e(TAG, "UVDoc inference failed", e)
            null
        }
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}

/** One inference on a synthetic page: runs, and predicts a finite grid. */
private fun Interpreter.passesSelfCheck(): Boolean = gpuPassesSelfCheck {
    val w = DocumentDewarpModel.INPUT_WIDTH
    backwardMap(IntArray(w * DocumentDewarpModel.INPUT_HEIGHT) { i -> if ((i % w) / 16 % 2 == 0) -0x1 else -0x1000000 })
}

/** [check]'s grid exists and is finite; a throw counts as a failure. Pure, for the JVM tests. */
internal fun gpuPassesSelfCheck(check: () -> BackwardMap?): Boolean =
    try { check()?.isFinite == true } catch (e: Exception) { false }

/** UVDoc on [pixels] (ARGB, 488 x 712), or null when the output shape is unexpected. Throws on a runtime failure. */
private fun Interpreter.backwardMap(pixels: IntArray): BackwardMap? {
    val n = pixels.size

    // RGB 0..1 — the 1/255 lives here rather than in the graph. Layout
    // follows whatever the converter emitted: [1,3,H,W] or [1,H,W,3].
    val inShape = getInputTensor(0).shape()
    val nchw = inShape.size == 4 && inShape[1] == 3
    val input = ByteBuffer.allocateDirect(3 * n * 4).order(ByteOrder.nativeOrder())
    if (nchw) {
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until n) input.putFloat((pixels[i] ushr shift and 0xff) / 255f)
        }
    } else {
        for (i in 0 until n) {
            val p = pixels[i]
            input.putFloat((p ushr 16 and 0xff) / 255f)
            input.putFloat((p ushr 8 and 0xff) / 255f)
            input.putFloat((p and 0xff) / 255f)
        }
    }
    input.rewind()

    val outShape = getOutputTensor(0).shape() // [1, 2, gh, gw] or [1, gh, gw, 2]
    if (outShape.size != 4) return null
    val channelsFirst = outShape[1] == 2
    val gh = if (channelsFirst) outShape[2] else outShape[1]
    val gw = if (channelsFirst) outShape[3] else outShape[2]
    val output = ByteBuffer.allocateDirect(2 * gh * gw * 4).order(ByteOrder.nativeOrder())
    run(input, output)
    output.rewind()
    val x = FloatArray(gw * gh)
    val y = FloatArray(gw * gh)
    if (channelsFirst) {
        for (i in x.indices) x[i] = output.getFloat()
        for (i in y.indices) y[i] = output.getFloat()
    } else {
        for (i in x.indices) {
            x[i] = output.getFloat()
            y[i] = output.getFloat()
        }
    }
    return BackwardMap(gw, gh, x, y)
}

class DewarpResult(
    val bitmap: Bitmap,
    val corrected: Boolean,
    /** Worst departure of the map from identity, as a fraction of the image. */
    val deviation: Double,
    val modelMs: Long,
    val resampleMs: Long,
)

object DocumentDewarp {
    /**
     * Straightens a deformed page. Returns the original untouched when the model
     * is unavailable or the page is already flat.
     */
    fun dewarp(
        model: DocumentDewarpModel,
        page: Bitmap,
    ): DewarpResult {
        val t0 = System.nanoTime()
        val map = model.backwardMap(page)
        val modelMs = (System.nanoTime() - t0) / 1_000_000
        if (map == null) {
            Log.i("DocumentDewarp", "page=${page.width}x${page.height} no map (model unavailable) after ${modelMs}ms")
            return DewarpResult(page, false, 0.0, modelMs, 0)
        }
        val deviation = map.deviationFromIdentity
        if (deviation < DocumentResampler.MINIMUM_DEVIATION) {
            Log.i("DocumentDewarp", "page=${page.width}x${page.height} flat deviation=%.4f model=%dms".format(deviation, modelMs))
            return DewarpResult(page, false, deviation, modelMs, 0)
        }

        val t1 = System.nanoTime()
        val w = page.width
        val h = page.height
        val out = DocumentResampler.resample(pixels(page), w, h, map)   // the source pixels are garbage once it returns
        val straightened = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val resampleMs = (System.nanoTime() - t1) / 1_000_000
        Log.i("DocumentDewarp", "page=${w}x$h deviation=%.3f model=%dms resample=%dms (${DocumentResampler.lastPath})".format(deviation, modelMs, resampleMs))
        return DewarpResult(straightened, true, deviation, modelMs, resampleMs)
    }
}

/** [bitmap]'s ARGB pixels, row-major. */
internal fun pixels(bitmap: Bitmap): IntArray =
    IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
