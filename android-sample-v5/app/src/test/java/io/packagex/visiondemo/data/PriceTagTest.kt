package io.packagex.visiondemo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    private val today = LocalDate.of(2026, 10, 8)

    @Test fun anExpiryBeforeTodayIsExpired() {
        val t = PriceTag.from("9100101", "$3.49", "2026-09-30", "EXP 2026-09-30", today)
        assertTrue(t.valid); assertTrue(t.expired)
        assertEquals("Fresh Milk 1L", t.name)
        assertEquals("Expired 30 Sep 2026", t.expiryLabel)
        assertEquals("EXP 2026-09-30", t.expiryText)
    }

    @Test fun theExpiryDayItselfIsNotExpired() {
        val t = PriceTag.from("9100103", "$2.99", "2026-10-08", "Use by 08/10/2026", today)
        assertFalse(t.expired); assertEquals("Expires 8 Oct 2026", t.expiryLabel)
    }

    @Test fun aLaterExpiryIsNotExpired() = assertFalse(PriceTag.from("9100105", "$6.49", "2027-03-31", "BB 03/2027", today).expired)

    @Test fun noOrUnreadableExpiryIsNeitherExpiredNorLabelled() {
        val none = PriceTag.from("14438", "$28.99", today = today)
        assertNull(none.expiry); assertFalse(none.expired); assertNull(none.expiryLabel)
        val bad = PriceTag.from("14438", "$28.99", "30/09/2026", "EXP 30/09/2026", today)   // not ISO: the SDK always sends ISO
        assertNull(bad.expiry); assertNull(bad.expiryText); assertFalse(bad.expired)
    }

    @Test fun theSixExpiryDemoTagsAreListed() {
        listOf("9100101", "9100102", "9100103", "9100104", "9100105", "9100106").forEach {
            assertTrue(it, PriceList.prices.containsKey(it) && PriceList.productNames.containsKey(it))
        }
    }
}
