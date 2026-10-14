package io.packagex.visiondemo.ar

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
    @Test
    fun `vision-barcode-scanner is gone and the engine is BarcodeScannerApp's`() {
        assertFalse(onClasspath("com.packagexlabs.visionbarcodescanner.VisionBarcodeScanner"))
        assertTrue(onClasspath("com.example.barcodescanner.BarcodeScanner"))
    }

    @Test
    fun `a scanner that cannot be made leaves the processor closable`() {
        // The JVM has no libbarcode_decoder.so: BarcodeScanner.create throws on the worker, which logs it.
        val processor = BarcodeProcessor(RuntimeEnvironment.getApplication())
        processor.close()
        processor.close() // a second close is harmless
    }

    @Test
    fun `the session re-reads every shown barcode in every frame`() {
        assertEquals(0L, AR_REFRESH_AFTER_MS)
    }

    @Test
    fun `the counter's refresh is used from its first view, the constant before`() {
        assertEquals(0L, refreshFor(null, AR_REFRESH_AFTER_MS))
        assertEquals(300L, refreshFor(300, AR_REFRESH_AFTER_MS))
        assertEquals(0L, refreshFor(0, 250L))
        assertEquals(0L, refreshFor(-5, 250L))
        assertEquals(250L, refreshFor(null, 250L))
    }

    private fun onClasspath(name: String) = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
}
