package io.packagex.visiondemo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceTagTest {
    @Test fun skuSuffixDroppedAndPriceMatched() {
        val t = PriceTag.from("14438-7", "$28.99")
        assertEquals("14438", t.sku); assertTrue(t.valid)
        assertEquals("Nescafe Classic Coffee", t.name); assertEquals("$28.99", t.expected)
    }

    @Test fun thousandsSeparatorIgnored() = assertTrue(PriceTag.from("2992088", "$1,299.99").valid)

    @Test fun wrongPriceOrUnknownSkuInvalid() {
        assertFalse(PriceTag.from("14438", "$20.00").valid)
        val u = PriceTag.from("nope", "$1")
        assertFalse(u.valid); assertEquals("Not Found", u.expected); assertEquals("nope", u.name)
    }
}
