package io.packagex.visiondemo.ar

import com.example.barcodescanner.FrameBarcode
import com.example.barcodescanner.FrameStats
import com.example.barcodescanner.ScanFrame
import com.example.barcodescanner.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeProcessorTest {
    // A box centred at (0.25, 0.5) of the upright frame.
    private val corners = floatArrayOf(0.2f, 0.45f, 0.3f, 0.45f, 0.3f, 0.55f, 0.2f, 0.55f)

    @Test
    fun `the centre of an upright box maps back to raw sensor pixels for each rotation`() {
        // Raw 1920 x 1080; turned upright by 90 it is 1080 x 1920: centre (270, 960).
        assertEquals(960f to 810f, uprightCentreToRaw(corners, 1080, 1920, 90, 1920, 1080).rounded())
        // Upright == raw for 0: centre (480, 540).
        assertEquals(480f to 540f, uprightCentreToRaw(corners, 1920, 1080, 0, 1920, 1080).rounded())
        // 180: (1920 - 480, 1080 - 540).
        assertEquals(1440f to 540f, uprightCentreToRaw(corners, 1920, 1080, 180, 1920, 1080).rounded())
        // 270: upright 1080 x 1920, centre (270, 960) -> (1920 - 960, 270).
        assertEquals(960f to 270f, uprightCentreToRaw(corners, 1080, 1920, 270, 1920, 1080).rounded())
    }

    @Test
    fun `only read barcodes with text become detections`() {
        val frame =
            ScanFrame(
                listOf(
                    FrameBarcode(1, null, null, corners), // a box the engine has not read yet
                    FrameBarcode(2, "", Symbology.CODE_128, corners), // read as nothing
                    FrameBarcode(3, "0123456789", Symbology.CODE_128, corners),
                    FrameBarcode(4, "TP-1", null, corners), // read, a symbology the SDK has no name for
                ),
                1080,
                1920,
                FrameStats(30f, 4, 3, 1f, 10f, 5f),
            )

        val detections = detectionsOf(frame, rotationDegrees = 90, rawWidth = 1920, rawHeight = 1080)

        assertEquals(listOf("0123456789" to "code128", "TP-1" to "Barcode"), detections.map { it.payload to it.format })
        assertEquals(960f to 810f, (detections[0].rawX to detections[0].rawY).rounded())
    }

    @Test
    fun `vision-barcode-scanner is gone and the engine is BarcodeScannerApp's`() {
        assertFalse(onClasspath("com.packagexlabs.visionbarcodescanner.VisionBarcodeScanner"))
        assertTrue(onClasspath("com.example.barcodescanner.BarcodeScanner"))
    }

    @Test
    fun `a scanner that cannot be made leaves the processor idle and closable`() {
        // The JVM has no libbarcode_decoder.so: BarcodeScanner.create throws on the worker, which logs it.
        val processor = BarcodeProcessor(RuntimeEnvironment.getApplication())
        processor.awaitIdle(5_000)
        assertFalse(processor.isBusy)
        processor.close()
        processor.close() // a second close is harmless
    }

    private fun onClasspath(name: String) = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    private fun Pair<Float, Float>.rounded() = Math.round(first).toFloat() to Math.round(second).toFloat()
}
