package io.packagex.visiondemo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesTest {
    @Test fun isCodeMatchesBarcodeQrPriceAndRetrieval() {
        val expected = setOf(ScanMode.Barcode, ScanMode.QR, ScanMode.Price, ScanMode.Retrieval)
        assertEquals(expected, ScanMode.entries.filter { it.isCode }.toSet())
    }

    @Test fun gatedMatchesPriceAndRetrieval() {
        val expected = setOf(ScanMode.Price, ScanMode.Retrieval)
        assertEquals(expected, ScanMode.entries.filter { it.gated }.toSet())
    }

    // The viewfinder brackets only ever show for single-code Barcode/QR scanning.
    @Test fun viewfinderOnlyHasARectForBarcodeAndQr() {
        val expected = setOf(ScanMode.Barcode, ScanMode.QR)
        assertEquals(expected, ScanMode.entries.filter { it.viewfinder != null }.toSet())
    }

    @Test fun bracketsVisibleForSingleBarcodeOrQr() {
        assertTrue(viewfinderBracketsVisible(ScanMode.Barcode, multi = false, hasResult = false, gated = false, permissionDenied = false))
        assertTrue(viewfinderBracketsVisible(ScanMode.QR, multi = false, hasResult = false, gated = false, permissionDenied = false))
    }

    @Test fun bracketsHiddenForMultiCodeBarcodeOrQr() {
        assertFalse(viewfinderBracketsVisible(ScanMode.Barcode, multi = true, hasResult = false, gated = false, permissionDenied = false))
        assertFalse(viewfinderBracketsVisible(ScanMode.QR, multi = true, hasResult = false, gated = false, permissionDenied = false))
    }

    @Test fun bracketsHiddenForEveryOtherMode() {
        for (mode in ScanMode.entries.filter { it != ScanMode.Barcode && it != ScanMode.QR }) {
            assertFalse(
                "expected brackets hidden for $mode",
                viewfinderBracketsVisible(mode, multi = false, hasResult = false, gated = false, permissionDenied = false),
            )
        }
    }

    @Test fun bracketsHiddenWhenResultShownGatedOrPermissionDenied() {
        assertFalse(viewfinderBracketsVisible(ScanMode.Barcode, multi = false, hasResult = true, gated = false, permissionDenied = false))
        assertFalse(viewfinderBracketsVisible(ScanMode.Barcode, multi = false, hasResult = false, gated = true, permissionDenied = false))
        assertFalse(viewfinderBracketsVisible(ScanMode.Barcode, multi = false, hasResult = false, gated = false, permissionDenied = true))
    }
}
