package io.packagex.visiondemo.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/** UVDoc on each path, the GPU guard, and the per-page model time for each (logged, tag DewarpBench). */
class DocumentDewarpModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun page(): Bitmap = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
        for (y in 200 until 2200 step 60) c.drawLine(150f, y.toFloat(), 1650f, y + 20f, p)
    }

    /** Loads the model, checks it with [assertions], then logs the median per-page time. */
    private fun bench(model: () -> DocumentDewarpModel, assertions: (DocumentDewarpModel) -> Unit = {}) {
        val load = System.nanoTime()
        model().use { m ->
            val loadMs = (System.nanoTime() - load) / 1_000_000
            assertTrue(m.isAvailable)
            assertions(m)
            val bitmap = page()
            assertNotNull(m.backwardMap(bitmap))   // first run, not timed
            val runs = (1..5).map {
                val t = System.nanoTime()
                assertNotNull(m.backwardMap(bitmap))
                (System.nanoTime() - t) / 1_000_000
            }
            Log.i("DewarpBench", "path=${if (m.usingGpu) "GPU" else "CPU"} load=${loadMs}ms per-page=${runs.sorted()[runs.size / 2]}ms runs=$runs")
        }
    }

    @Test fun runsOnCpu() = bench({ DocumentDewarpModel(context, preferGpu = false) }) { assertFalse(it.usingGpu) }

    /** Skipped (not passed) where the GPU delegate can't take UVDoc, so a pass always means a GPU run (one that
     *  also passed the real self-check). */
    @Test fun runsOnGpu() = bench({ DocumentDewarpModel(context) }) { assumeTrue("GPU delegate unavailable here", it.usingGpu) }

    /** fp16 on the GPU predicts the same sampling grid as fp32 on the CPU (normalised coordinates). */
    @Test fun gpuMatchesCpu() {
        val bitmap = page()
        DocumentDewarpModel(context).use { gpu ->
            assumeTrue("GPU delegate unavailable here", gpu.usingGpu)
            val g = gpu.backwardMap(bitmap)!!
            val c = DocumentDewarpModel(context, preferGpu = false).use { assertFalse(it.usingGpu); it.backwardMap(bitmap) }!!
            val diff = maxOf(c.x.indices.maxOf { abs(c.x[it] - g.x[it]) }, c.y.indices.maxOf { abs(c.y[it] - g.y[it]) })
            Log.i("DewarpBench", "path=GPU vs CPU max |gpu - cpu| = $diff")
            assertTrue("max diff $diff", diff < 0.01f)
        }
    }

    /** A GPU interpreter that fails the self-check is dropped for the CPU, and pages still dewarp. */
    @Test fun failedGpuSelfCheckFallsBackToCpu() {
        var checked = false
        DocumentDewarpModel(context, preferGpu = true, gpuCheck = { checked = true; false }).use { m ->
            assumeTrue("GPU delegate unavailable here: no GPU interpreter to reject", checked)
            assertFalse(m.usingGpu)
            assertTrue(m.isAvailable)
            assertNotNull(m.backwardMap(page()))
            Log.i("DewarpBench", "self-check ran=$checked, path=CPU after a failed GPU self-check")
        }
    }
}
