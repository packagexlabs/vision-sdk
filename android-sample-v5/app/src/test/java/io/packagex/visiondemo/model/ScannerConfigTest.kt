package io.packagex.visiondemo.model

import io.packagex.visionsdk.core.DetectionMode
import org.junit.Assert.*
import org.junit.Test

class ScannerConfigTest {
    @Test fun singleModeRestrictsToFrameAndHidesBoxes() {
        val c = scannerConfig(ScanMode.Barcode, multi = false, showBoxesPref = true)
        assertTrue(c.restrictToFrame); assertFalse(c.showBoxes); assertEquals(7, c.nthFrame)
        assertEquals(DetectionMode.Barcode, c.detection)
    }
    @Test fun multiModeShowsBoxesAndScansWholeFrame() {
        val c = scannerConfig(ScanMode.Barcode, multi = true, showBoxesPref = true)
        assertFalse(c.restrictToFrame); assertTrue(c.showBoxes); assertTrue(c.multiple)
    }
    @Test fun visionScannerShowsBoxes() = assertTrue(scannerConfig(ScanMode.Ocr, false, true).showBoxes)
    @Test fun boxesOffWhenPrefOff() = assertFalse(scannerConfig(ScanMode.Barcode, true, false).showBoxes)
    @Test fun retrievalEveryOtherFrameAndGated() {
        val c = scannerConfig(ScanMode.Retrieval, false, true)
        assertEquals(2, c.nthFrame); assertTrue(c.needsEntitlement); assertNull(c.detection)
    }
    @Test fun priceTagSeventhFrame() = assertEquals(7, scannerConfig(ScanMode.Price, false, true).nthFrame)
    @Test fun dialHasNoDimOrTextTemplates() =
        assertEquals(listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "Item retrieval", "AR Barcode", "Document Acquisition"),
                     ScanMode.entries.map { it.label })
}
