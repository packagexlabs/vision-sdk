package io.packagex.visiondemo.camera

import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.scannerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnershipTest {
    @Test fun arStopsScanner() = assertTrue(scannerMustStop(CameraOwner.Ar))
    @Test fun documentStopsScanner() = assertTrue(scannerMustStop(CameraOwner.Document))
    @Test fun scannerKeepsIt() = assertFalse(scannerMustStop(CameraOwner.Scanner))

    @Test fun singleModeRestrictsToFrame() {
        val fs = focusSettingsFor(scannerConfig(ScanMode.Barcode, multi = false, showBoxesPref = true), Box(0, 100, 300, 200))
        assertTrue(fs.restrict); assertEquals(Box(0, 100, 300, 200), fs.rect)
    }

    @Test fun sdkDrawsTheBoxesOnlyInCodeMultipleScan() {
        assertTrue(focusSettingsFor(scannerConfig(ScanMode.Barcode, multi = true, showBoxesPref = true), null).sdkBoxes)
        assertFalse(focusSettingsFor(scannerConfig(ScanMode.Barcode, multi = false, showBoxesPref = true), Box(0, 100, 300, 200)).sdkBoxes)
        assertFalse(focusSettingsFor(scannerConfig(ScanMode.Ocr, multi = false, showBoxesPref = true), null).sdkBoxes)
    }
}
