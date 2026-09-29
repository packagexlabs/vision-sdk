package io.packagex.visiondemo.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** UVDoc loads and runs on both paths; logs the per-page model time for each (tag DewarpBench). */
class DocumentDewarpModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun page(): Bitmap = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
        for (y in 200 until 2200 step 60) c.drawLine(150f, y.toFloat(), 1650f, y + 20f, p)
    }

    private fun bench(preferGpu: Boolean): Boolean {
        val load = System.nanoTime()
        DocumentDewarpModel(context, preferGpu).use { m ->
            val loadMs = (System.nanoTime() - load) / 1_000_000
            assertTrue(m.isAvailable)
            val bitmap = page()
            assertNotNull(m.backwardMap(bitmap))   // first run, not timed
            val runs = (1..5).map {
                val t = System.nanoTime()
                assertNotNull(m.backwardMap(bitmap))
                (System.nanoTime() - t) / 1_000_000
            }
            Log.i("DewarpBench", "gpu=${m.usingGpu} load=${loadMs}ms per-page=${runs.sorted()[runs.size / 2]}ms runs=$runs")
            return m.usingGpu
        }
    }

    @Test fun runsOnCpu() { bench(preferGpu = false) }

    @Test fun runsOnGpuWhenSupported() { bench(preferGpu = true) }

    /** fp16 on the GPU predicts the same sampling grid as fp32 on the CPU (normalised coordinates). */
    @Test fun gpuMatchesCpu() {
        val bitmap = page()
        val cpu = DocumentDewarpModel(context, preferGpu = false).use { it.backwardMap(bitmap) }!!
        DocumentDewarpModel(context).use { m ->
            val gpu = m.backwardMap(bitmap)!!
            val diff = maxOf(cpu.x.indices.maxOf { abs(cpu.x[it] - gpu.x[it]) }, cpu.y.indices.maxOf { abs(cpu.y[it] - gpu.y[it]) })
            Log.i("DewarpBench", "gpu=${m.usingGpu} max |gpu - cpu| = $diff")
            assertTrue("max diff $diff", diff < 0.01f)
        }
    }
}
