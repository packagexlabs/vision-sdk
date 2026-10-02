package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.scanner.OcrOutcome
import io.packagex.visiondemo.scanner.ocrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmPromptsTest {
    private fun fields(response: String, type: DocType): List<VlmPrompts.Field> {
        val spec = VlmPrompts.spec(type)!!
        return VlmPrompts.fields(VlmPrompts.answer(response)!!, spec.order, keepDocumentType = type == DocType.IdCard)
    }

    /** The real staging response shape: everything but `model_response` is request bookkeeping. */
    private fun response(answer: String) = """{"status":201,"message":"VLM Inference created successfully","data":{"object":"vlm_inference",
        "id":"inf_vlm_x","organization":{"id":"org_x","name":"Org"},"status":"completed","image_hash":"abc","model":"orion_small",
        "prompt":"You are reading…","model_response":[$answer],"token_count":403,"metadata":{},"checksum":"c"}}"""

    @Test fun onlyTheAnswerInPromptOrder() {
        val f = fields(response("""{"document_type":"LICENSE_PLATE","confidence":0.93,"plate_number":"LEA-9999","readable":true,"country":"PK","zzz_extra":"x"}"""), DocType.Plate)
        assertEquals(listOf("Plate Number", "Plate (Normalized)", "Validation", "Country", "Readable", "Confidence", "Zzz Extra"), f.map { it.label })
        assertEquals(listOf("LEA-9999", "LEA9999", "Valid", "PK", "Yes", "0.93", "x"), f.map { it.value })
    }

    @Test fun jsonInMarkdownFence() {
        val fenced = "\"```json\\n{\\\"reading\\\":\\\"001234.5\\\",\\\"unit\\\":\\\"kWh\\\",\\\"meter_type\\\":\\\"ELECTRIC\\\"}\\n```\""
        val f = fields(response(fenced), DocType.Meter)
        assertEquals(listOf("Meter Type" to "ELECTRIC", "Reading" to "001234.5", "Unit" to "kWh"), f.map { it.label to it.value })
    }

    @Test fun nullAndEmptySkipped() {
        val f = fields(response("""{"document_type":"METER_READING","meter_type":"GAS","reading":"04821","unit":"m³","meter_serial_number":null,
            "register_count":null,"registers":null,"date_time":"","confidence_notes":"null"}"""), DocType.Meter)
        assertEquals(listOf("Meter Type", "Reading", "Unit"), f.map { it.label })
        assertTrue(f.none { it.label == "Document Type" })
    }

    @Test fun nestedListsBecomeSectionsAfterTopLevel() {
        val f = fields(response("""{"meter_type":"ELECTRIC","reading":"012345","unit":"kWh","register_count":2,
            "registers":[{"label":"T1","reading":"008000","unit":"kWh"},{"label":"T2","reading":"004345","unit":null}],
            "meter_serial_number":"SN-77"}"""), DocType.Meter)
        assertEquals(
            listOf(
                Triple(null, "Meter Type", "ELECTRIC"), Triple(null, "Reading", "012345"), Triple(null, "Unit", "kWh"),
                Triple(null, "Meter Serial Number", "SN-77"), Triple(null, "Register Count", "2"),
                Triple("Registers 1", "Label", "T1"), Triple("Registers 1", "Reading", "008000"), Triple("Registers 1", "Unit", "kWh"),
                Triple("Registers 2", "Label", "T2"), Triple("Registers 2", "Reading", "004345"),
            ),
            f.map { Triple(it.section, it.label, it.value) },
        )
    }

    @Test fun nestedObjectAndScalarList() {
        val a = VlmPrompts.answer(response("""{"vin":"1HGCM82633A004352","extra":{"b":"2","a":{"x":"1"}},"tags":["a",null,"b"]}"""))!!
        val f = VlmPrompts.fields(a, VlmPrompts.vehicleTireOrder, keepDocumentType = false)
        assertEquals(
            listOf(
                Triple(null, "Vin", "1HGCM82633A004352"), Triple(null, "VIN Checksum", "Valid"), Triple(null, "Tags", "a, b"),
                Triple("Extra", "B", "2"), Triple("Extra · A", "X", "1"),
            ),
            f.map { Triple(it.section, it.label, it.value) },
        )
    }

    @Test fun idCardKeepsDocumentType() {
        val f = fields(response("""{"document_type":"PASSPORT","surname":"DOE","issuing_country":"PAK"}"""), DocType.IdCard)
        assertEquals(listOf("Document Type", "Issuing Country", "Surname"), f.map { it.label })
    }

    @Test fun nonJsonAnswerFallsBackToText() {
        val a = VlmPrompts.answer(response("\"I can't see a meter in this photo.\""))
        assertEquals(VlmPrompts.Answer.Text("I can't see a meter in this photo."), a)
        assertEquals(listOf("Response" to "I can't see a meter in this photo."), VlmPrompts.fields(a!!, emptyList(), false).map { it.label to it.value })
    }

    @Test fun proseAroundJsonStillParses() {
        val a = VlmPrompts.answerFromOutput(kotlinx.serialization.json.JsonPrimitive("Here you go: {\"reading\":\"0042\"} hope that helps"))
        assertEquals("0042", (a as VlmPrompts.Answer.Obj).json["reading"].toString().trim('"'))
    }

    @Test fun noAnswer() {
        assertNull(VlmPrompts.answer("""{"message":"bad","data":{"model_response":[]}}"""))
        assertNull(VlmPrompts.answer("not json"))
    }

    @Test fun meterResultShowsReadingFirstAndNoBookkeeping() {
        val json = response("""{"document_type":"METER_READING","meter_type":"WATER","reading":"00123.456","unit":"m³","meter_serial_number":"W-1"}""")
        val out = ocrResult(Extraction(DocType.Meter, json), null, Prefs(docType = DocType.Meter), cloudSelected = true, seconds = 1.0) as OcrOutcome.Done
        assertEquals("Meter Reading", out.result.title)
        assertEquals("Cloud · VLM", out.result.subtitle)
        assertEquals("00123.456", out.result.result.primary?.value)
        assertEquals(listOf("meter_type", "reading", "unit", "meter_serial_number"), out.result.result.fields.map { it.key })
    }

    @Test fun defaultVlmListsOnlyTheAnswer() {
        val json = response("""{"document_type":"invoice","invoice_number":"INV-9","total":"12.00"}""")
        val out = ocrResult(Extraction(DocType.VLM, json), null, Prefs(docType = DocType.VLM), cloudSelected = true, seconds = 1.0) as OcrOutcome.Done
        assertEquals("Invoice", out.result.title)
        assertEquals(setOf("invoice_number", "total"), out.result.result.fields.map { it.key }.toSet())
    }

    @Test fun meterSpec() {
        val s = VlmPrompts.spec(DocType.Meter)!!
        assertEquals("Meter Reading", s.title)
        assertEquals(listOf("meter_type", "reading", "unit", "meter_serial_number", "register_count", "registers", "date_time", "confidence_notes"), s.order)
        assertTrue(s.prompt.contains("keep leading zeros"))
        assertNull(VlmPrompts.spec(DocType.VLM))
    }
}
