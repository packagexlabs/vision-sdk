package io.packagex.visiondemo.document

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * TFLite wrapper around UVDoc (tanguymagne/UVDoc, MIT), the document unwarping
 * network from "UVDoc: Neural Grid-based Document Unwarping" (SIGGRAPH Asia
 * 2023). It takes a fixed 488 × 712 view of the page and predicts a coarse grid
 * of normalised sampling coordinates — see [BackwardMap].
 *
 * Same shipping format as the barcode detector: fp16 weights, float32 I/O, run
 * through the Play Services TFLite runtime. Exported by
 * scripts/export_uvdoc_tflite.py from the checkpoint the iOS demo ships as
 * UVDoc.mlmodelc.
 */
class DocumentDewarpModel(
    context: Context,
) : AutoCloseable {
    companion object {
        /** The input size UVDoc was trained at; not negotiable. */
        const val INPUT_WIDTH = 488
        const val INPUT_HEIGHT = 712
        private const val ASSET = "uvdoc_fp16.tflite"
        private const val TAG = "DocumentDewarp"
    }

    /** True when the interpreter runs on the GMS GPU delegate (fp16), false for CPU/XNNPACK. */
    var usingGpu = false
        private set

    private val interpreter: InterpreterApi? =
        try {
            // GPU-enabled init first (fails outright on devices whose GMS can't
            // load the GPU module — the Memor 35 among them); plain init after.
            // The GMS client aborts the process if any TFLite API runs before
            // initialize() succeeds; the barcode analyzer does the same dance.
            val gpuInit =
                try {
                    Tasks.await(TfLite.initialize(context, TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(true).build()), 2, TimeUnit.MINUTES)
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "GPU-enabled TFLite init failed; CPU-only init", e)
                    Tasks.await(TfLite.initialize(context), 2, TimeUnit.MINUTES)
                    false
                }
            val gpuAvailable =
                gpuInit &&
                    try {
                        Tasks.await(TfLiteGpu.isGpuDelegateAvailable(context), 2, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        false
                    }
            val model =
                context.assets.open(ASSET).use { it.readBytes() }.let { bytes ->
                    ByteBuffer
                        .allocateDirect(bytes.size)
                        .order(ByteOrder.nativeOrder())
                        .put(bytes)
                        .apply { rewind() }
                }
            val options =
                InterpreterApi
                    .Options()
                    .setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                    .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            if (gpuAvailable) {
                // 18 GMACs of plain convs: fp16 GPU is the difference between ~1 s
                // and ~0.1–0.2 s per page. Serialized kernels make the second
                // open fast.
                // Play Services TFLite only accepts delegate *factories*; the
                // interpreter owns the delegate it creates.
                options.addDelegateFactory(
                    GpuDelegateFactory(
                        GpuDelegateFactory
                            .Options()
                            .setPrecisionLossAllowed(true)
                            .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
                            .setSerializationParams(File(context.cacheDir, "uvdoc_gpu").apply { mkdirs() }.absolutePath, "uvdoc-fp16-v1"),
                    ),
                )
            }
            try {
                InterpreterApi.create(model, options).also { usingGpu = gpuAvailable }
            } catch (e: Exception) {
                if (!gpuAvailable) throw e
                // Driver rejected the graph: drop to CPU rather than fail the page.
                Log.w(TAG, "GPU delegate rejected UVDoc; using CPU", e)
                InterpreterApi.create(
                    model,
                    InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY).setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)),
                )
            }.also { Log.i(TAG, "UVDoc ready gpu=$usingGpu") }
        } catch (e: Exception) {
            Log.e(TAG, "UVDoc unavailable", e)
            null
        }

    val isAvailable: Boolean get() = interpreter != null

    /**
     * Predicts the backward map for a page, or null when the model is missing.
     * Synchronized: one interpreter, and TFLite's run() is not thread-safe —
     * two concurrent pages segfaulted inside the GMS runtime.
     */
    @Synchronized
    fun backwardMap(page: Bitmap): BackwardMap? {
        val interpreter = interpreter ?: return null
        val scaled = Bitmap.createScaledBitmap(page, INPUT_WIDTH, INPUT_HEIGHT, true)
        val n = INPUT_WIDTH * INPUT_HEIGHT
        val pixels = IntArray(n)
        scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        if (scaled !== page) scaled.recycle()

        // RGB 0..1 — the 1/255 lives here rather than in the graph. Layout
        // follows whatever the converter emitted: [1,3,H,W] or [1,H,W,3].
        val inShape = interpreter.getInputTensor(0).shape()
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

        val outShape = interpreter.getOutputTensor(0).shape() // [1, 2, gh, gw] or [1, gh, gw, 2]
        if (outShape.size != 4) return null
        val channelsFirst = outShape[1] == 2
        val gh = if (channelsFirst) outShape[2] else outShape[1]
        val gw = if (channelsFirst) outShape[3] else outShape[2]
        val output = ByteBuffer.allocateDirect(2 * gh * gw * 4).order(ByteOrder.nativeOrder())
        return try {
            interpreter.run(input, output)
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
            BackwardMap(gw, gh, x, y)
        } catch (e: Exception) {
            Log.e(TAG, "UVDoc inference failed", e)
            null
        }
    }

    override fun close() {
        interpreter?.close()
    }
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
