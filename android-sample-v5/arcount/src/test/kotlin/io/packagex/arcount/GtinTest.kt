package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {
    @Test
    fun upcAAndEan13OfOneProductAreOneGtin() {
        assertEquals("00036000291452", Gtin.normalize("036000291452"))
        assertEquals("00036000291452", Gtin.normalize("0036000291452"))
    }

    @Test
    fun ean8AndGtin14() {
        assertEquals("00000096385074", Gtin.normalize("96385074"))
        assertEquals("10036000291459", Gtin.normalize("10036000291459"))
    }

    @Test
    fun upcEIsExpandedToUpcABeforePadding() {
        assertEquals("00042100005264", Gtin.normalize("04252614", "UPC_E"))
        assertEquals("00012300000453", Gtin.normalize("01234533", "upcE"))
        assertEquals("00012340000054", Gtin.normalize("01234544", "UPC-E"))
        assertEquals("00012345000077", Gtin.normalize("01234577", "upce"))
        // the same eight digits as EAN-8
        assertEquals("00000004252614", Gtin.normalize("04252614", "EAN_8"))
    }

    @Test
    fun otherPayloadsStayAsTheyAre() {
        assertEquals("LABEL-1", Gtin.normalize("LABEL-1"))
        assertEquals("1234567890", Gtin.normalize("1234567890"))
        assertTrue(Gtin.isGtin("036000291452"))
        assertFalse(Gtin.isGtin("LABEL-1"))
        assertFalse(Gtin.isGtin("1234567890"))
    }
}
