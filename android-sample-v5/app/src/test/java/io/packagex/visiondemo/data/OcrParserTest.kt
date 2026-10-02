package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {
    private val sl = """{"data":{"tracking_number":"1Z999","provider_name":"UPS","weight":"2 lb",
        "recipient":{"name":"Ada","address":{"line1":"1 Main St","postal_code":"10001"}},
        "raw_text":"noise","confidence":0.9}}"""

    @Test fun slOrderLabelsAndPrimary() {
        val r = OcrParser.parse(sl, DocType.SL)
        assertEquals("1Z999", r.primary?.value)
        assertEquals(listOf("Tracking #", "Courier", "Weight"), r.fields.take(3).map { it.label })
        assertTrue(r.fields.none { it.key == "raw_text" || it.key == "confidence" })
        assertEquals("Receiver Info", r.fields.first { it.key == "line1" }.section)
        assertEquals("Street Address", r.fields.first { it.key == "line1" }.label)
    }

    @Test fun tablesBecomeHeaderAndRows() {
        // Generic path (a default-prompt VLM answer's line items).
        val vlm = """{"data":{"inference":{"tables":[{"item":"Box","qty":"2"},{"item":"Crate","qty":"1"}]}}}"""
        val t = OcrParser.parse(vlm, DocType.VLM).tables.single()
        assertEquals(listOf("Item", "Qty"), t.headers)
        assertEquals(listOf(listOf("Box", "2"), listOf("Crate", "1")), t.rows)
    }

    @Test fun messageAndClass() {
        assertEquals("bad", OcrParser.message("""{"message":"bad"}"""))
        assertEquals("shipping_label", OcrParser.documentClass("""{"data":{"inference":{"document_class":"shipping_label"}}}"""))
    }

    @Test fun invalidJsonGivesEmptyResult() = assertTrue(OcrParser.parse("not json", DocType.SL).fields.isEmpty())

    @Test fun monoFollowsIosRegex() {
        val r = OcrParser.parse(sl, DocType.SL)
        assertTrue(r.fields.first { it.key == "weight" }.mono)          // "2 lb"
        assertTrue(!r.fields.first { it.key == "provider_name" }.mono)  // "UPS"
    }
}
