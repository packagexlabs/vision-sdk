package io.packagex.visiondemo.scanner

import io.packagex.visiondemo.data.PriceTag
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PriceDrawerTextTest {
    private val today = LocalDate.of(2026, 10, 8)
    private val tags = listOf(
        PriceTag.from("9100101", "$3.49", "2026-09-30", "EXP 2026-09-30", today),
        PriceTag.from("9100104", "$5.79", "2026-12-31", "EXP 31/12/2026", today),
        PriceTag.from("14438", "$20.00", today = today),
    )

    @Test fun theSubtitleCountsInvalidAndExpiredTags() {
        assertEquals("1 invalid · 1 expired", priceSubtitle(tags))
        assertEquals("1 invalid", priceSubtitle(tags.drop(1)))
    }

    @Test fun theCopyTextCarriesEachTagsExpiry() = assertEquals(
        "9100101\t$3.49\tValid\tExpired 30 Sep 2026\n9100104\t$5.79\tValid\tExpires 31 Dec 2026\n14438\t$20.00\tInvalid",
        priceSummary(tags),
    )
}
