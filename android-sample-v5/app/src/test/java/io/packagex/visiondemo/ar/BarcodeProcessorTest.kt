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
    fun `a scanner that cannot be made leaves the processor idle and closable`() {
        // The JVM has no libbarcode_decoder.so: BarcodeScanner.create throws on the worker, which logs it.
        val processor = BarcodeProcessor(RuntimeEnvironment.getApplication())
        assertFalse(processor.isBusy)
        processor.close()
        processor.close() // a second close is harmless
    }

    @Test
    fun `the session re-reads every shown barcode in every frame`() {
        assertEquals(0L, AR_REFRESH_AFTER_MS)
    }

    private fun onClasspath(name: String) = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
}
