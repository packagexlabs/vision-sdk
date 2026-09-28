package io.packagex.visiondemo.model

import org.junit.Assert.assertEquals
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
}
