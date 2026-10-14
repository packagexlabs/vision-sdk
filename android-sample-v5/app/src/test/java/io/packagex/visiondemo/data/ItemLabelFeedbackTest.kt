package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemLabelFeedbackTest {
    @Test fun correctionIsFlagged() {
        val r = OcrParser.parse("""{"data":{"inference":{"item":{"name":"Soap"}}}}""", DocType.IL)
        val f = r.fields.single()
        val p = ItemLabelFeedback.payload(r, mapOf(f.id to ItemLabelFeedback.Entry("Soap bar", thumbs = false)), nowSeconds = 1.0)
        val e = p["feedback_data"]!!.jsonArray.single().jsonObject
        assertEquals("Soap bar", e["corrected_value"]!!.jsonPrimitive.content)
        assertEquals(true, e["has_correction"]!!.jsonPrimitive.boolean)
        assertEquals("down", e["feedback_thumbs"]!!.jsonPrimitive.content)
    }
}
