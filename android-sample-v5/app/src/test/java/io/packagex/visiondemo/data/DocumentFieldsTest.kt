package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-type field selection ([DocumentFields] via [OcrParser.parse]) on real responses: the `*_cloud*` fixtures are
 * staging responses for the old Android sample's sample images, personal data replaced; the `*_device` ones follow the
 * Android SDK's on-device builders (SLModel, ILModel + ILResponseReconciler, BOLModel + BOLResponseReconciler).
 */
class DocumentFieldsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/ocr/$name.json")!!.readText()

    private fun rows(r: OcrResult) = r.fields.map { "${it.section} | ${it.label} | ${it.value}" }

    /** Request bookkeeping the old apps never showed. */
    private val bookkeeping = listOf("Uuid", "Hash", "Checksum", "Timings", "Options", "Matching", "Raw Text", "Organization", "Created", "Image")

    private fun assertNoBookkeeping(r: OcrResult) = r.fields.forEach { f ->
        assertTrue(f.label, bookkeeping.none { f.label.contains(it, ignoreCase = true) || (f.section ?: "").contains(it, ignoreCase = true) })
    }

    @Test fun shippingLabelCloud() {
        val r = OcrParser.parse(fixture("sl_cloud_1"), DocType.SL)
        assertEquals(
            listOf(
                "Package Info | Tracking # | 9402037903201896224902",
                "Package Info | Courier | USPS",
                // Business name equal to the name is not repeated.
                "Receiver Info | Name | Ada Lovelace",
                "Receiver Info | Street Address | 100 main st",
                "Receiver Info | City | Springfield",
                "Receiver Info | State | California",
                "Receiver Info | Zip Code | 00000",
                "Receiver Info | Address | 100 main st, springfield, CA, 00000, United States",
                "Sender Info | Name | Grace Hopper",
                "Sender Info | Street Address | 200 oak ave",
                "Sender Info | City | Riverton",
                "Sender Info | State | Virginia",
                "Sender Info | Zip Code | 00000",
                "Sender Info | Address | 200 oak ave, riverton, VA, 00000, United States",
                "Logistics Attributes | Shipment Type | Ground Advantage",
            ),
            rows(r),
        )
        assertEquals("9402037903201896224902", r.primary?.value)
        assertTrue(r.tables.isEmpty())
        assertNoBookkeeping(r)
    }

    @Test fun shippingLabelCloudMiddleMileFromTwoProviders() {
        val r = OcrParser.parse(fixture("sl_cloud_mm"), DocType.SL)
        assertEquals(
            listOf(
                "Middle Mile Info | Tracking # | 600678909631",
                "Middle Mile Info | Courier | FedEx",
                "Middle Mile Info | Shipment Type | Ground",
            ),
            rows(r).filter { it.startsWith("Middle Mile") },
        )
        assertEquals("9261290272309538451493", r.primary?.value)
        assertEquals(listOf("Ref # | TX1829029", "Shipment Type | Parcel Select"), rows(r).filter { it.startsWith("Logistics") }.map { it.substringAfter("| ") })
        assertNoBookkeeping(r)
    }

    @Test fun shippingLabelOnDevice() {
        val r = OcrParser.parse(fixture("sl_device"), DocType.SL)
        assertEquals(
            listOf(
                "Package Info | Account ID | ACCT-4471",
                "Package Info | Box ID | BX-12",
                "Package Info | Tracking # | 771234567890",
                "Package Info | Courier | FedEx",
                "Package Info | Weight | 2.2 lbs",
                "Package Info | Dimensions | 12 × 10 × 4 in",
                "Package Info | Special Handling Labels | Signature Required",
                "Receiver Info | Name | Ada Lovelace",
                "Receiver Info | Business Name | Example Corp",
                "Receiver Info | Street Address | 100 Main St Suite 2",
                "Receiver Info | City | Springfield",
                "Receiver Info | State | IL",
                "Receiver Info | Zip Code | 00000",
                "Receiver Info | Address | 100 Main St, Springfield, IL 00000",
                "Receiver Info | Phone | +15550100000",
                // The all-null middle_mile object adds no section.
                "Sender Info | Business Name | Example Parts Co",
                "Sender Info | Street Address | 200 Oak Ave",
                "Sender Info | City | Riverton",
                "Sender Info | State | TX",
                "Sender Info | Zip Code | 00000",
                "Sender Info | Address | 200 Oak Ave, Riverton, TX 00000",
                "Logistics Attributes | PO # | PO-778812",
                "Logistics Attributes | Shipment Type | Priority Overnight",
            ),
            rows(r),
        )
        // On-device boxes stay linked to their rows.
        val tracking = r.fields.first { it.key == "tracking_number" }
        assertEquals(4, tracking.vertices?.size)
        assertEquals(listOf("BARCODE", "APRIORI"), tracking.validatedBy)
        assertEquals(tracking, r.primary)
        assertTrue(r.fields.first { it.label == "Name" && it.section == "Receiver Info" }.vertices != null)
    }

    @Test fun itemLabelCloud() {
        val r = OcrParser.parse(fixture("il_cloud_1"), DocType.IL)
        assertEquals(
            listOf(
                "Receiver Info | Business Name | Ada Lovelace",
                "Receiver Info | Street Address | 100 main st",
                "Receiver Info | City | Springfield",
                "Receiver Info | Zip Code | 00000",
                "Receiver Info | Address | 100 main st, springfield, 00000",
                "Label Details | Purchase Orders | 174371087",
                "Label Details | Sales Orders | 174371087",
                "Label Details | Manufacturing Date | 11-7-2019",
                "Item Details | SKU | B07887XCRX",
                "Item Details | Quantity | 1",
                "Item Details | Weight | 47 lb",
                "Package Details | Weight | 47 lb",
                "Identifiers | Barcode Values | TB7063",
                // Vendor attributes the label fields don't already show (Sku, itemSku, Consignment, cartonId, … are).
                "Additional Attributes | Carrier | STD",
                "Additional Attributes | Deliver To | Sample Moving & Storage",
                "Additional Attributes | Hazardous? (Y/N) | N",
                "Additional Attributes | Load Sequence | 10",
                "Additional Attributes | Project ID | 19-3993D-0048",
                "Additional Attributes | Service Level | NOR",
                "Additional Attributes | Ship Dock | SHIP45",
            ),
            rows(r),
        )
        assertNoBookkeeping(r)
    }

    @Test fun itemLabelCloudDropsRepeatedAttributes() {
        val r = OcrParser.parse(fixture("il_cloud_2"), DocType.IL)
        assertEquals(
            listOf(
                "Label Details | Purchase Orders | 0015766831, 4500295404",
                "Label Details | Sales Orders | 13326",
                "Label Details | Manufacturing Date | 28-1-2022",
                "Label Details | Origin Country | Austria",
                "Item Details | Name | HPE C13-C14 WW 250V 10A 1.4m Jpr Cord",
                "Item Details | Numbers | 142257-006",
                "Item Details | Quantity | 2",
                "Item Details | UPC | 1332626879",
                "Item Details | Version | AR",
                "Identifiers | LOT Number | 000140856146",
                "Additional Attributes | /EAN | 948382 246219",
                "Additional Attributes | OverPack ID | OP-D0533719",
                "Additional Attributes | QTY | 2, 2",
                "Additional Attributes | UPC | 13326 26879",
            ),
            rows(r),
        )
    }

    @Test fun itemLabelOnDevice() {
        val r = OcrParser.parse(fixture("il_device"), DocType.IL)
        assertEquals(
            listOf(
                "Supplier Info | Name | Acme Electronics Co., Ltd.",
                "Label Details | Purchase Orders | 4500295404",
                "Label Details | Manufacturing Date | 15-3-2026",
                "Label Details | Origin Country | Taiwan",
                "Item Details | SKU | 900-11234-01",
                "Item Details | Name | AX-2000 Router",
                "Item Details | Numbers | P/N 900-11234-01",
                "Item Details | Quantity | 2",
                "Item Details | Color | Black",
                "Item Details | Model | AX-2000",
                "Item Details | UPC | 012345678905",
                "Item Details | Weight | 1.2 kg",
                "Item Details | Mac Address(es) | 00:1A:2B:3C:4D:5E",
                "Package Details | Raw Dimensions | 30 x 20 x 10 cm",
                "Package Details | Dimensions | Height: 10 cm, Width: 20 cm, Length: 30 cm",
                "Identifiers | LOT Number | L2026-09",
                "Identifiers | Serial Numbers | SN8812345XY",
                "Identifiers | Barcode Values | 012345678905",
                "Additional Attributes | Route | R-7",
            ),
            rows(r),
        )
        val upc = r.fields.first { it.label == "UPC" }
        assertEquals(listOf("BARCODE"), upc.validatedBy)
    }

    @Test fun billOfLadingCloud() {
        val r = OcrParser.parse(fixture("bol_cloud"), DocType.BOL)
        assertEquals(
            listOf(
                "Logistics Attributes | Master Bill of Lading # | 645350",
                "Logistics Attributes | Load # | 645350",
                "Logistics Attributes | Order # | Rhg540431902",
                "Logistics Attributes | PO # | 1677539",
                "Logistics Attributes | Reference # | Test",
                "Logistics Attributes | Shipping ID | 7768 5804 0439",
                "Logistics Attributes | Shipping Date | 2026-11-08",
                "Receiver Info | Business Name | Grace Hopper",
                "Receiver Info | Street Address | 200 oak ave",
                "Receiver Info | City | Riverton",
                "Receiver Info | State | Texas",
                "Receiver Info | Zip Code | 00000",
                "Receiver Info | Address | 200 oak ave, riverton, TX, 00000, United States",
                "Receiver Info | Phone | +15550100000",
                "Sender Info | Name | Ada Lovelace",
                "Sender Info | Street Address | 100 main st",
                "Sender Info | City | Springfield",
                "Sender Info | State | Florida",
                "Sender Info | Zip Code | 00000",
                "Sender Info | Address | 100 main st, springfield, FL, 00000, United States",
            ),
            rows(r),
        )
        // Columns come keyed "1"…"11" with the printed header as the first row; empty columns are dropped.
        val t = r.tables.single()
        assertEquals("Inventory Items", t.title)
        assertEquals(listOf("Pieces", "Description", "Weight", "Class"), t.headers)
        assertEquals(listOf(listOf("25", "WRAP ALPHABET DESIGN", "775", "M1")), t.rows)
        assertNoBookkeeping(r)
    }

    @Test fun billOfLadingOnDevice() {
        val r = OcrParser.parse(fixture("bol_device"), DocType.BOL)
        assertEquals(
            listOf(
                "Logistics Attributes | Customer PO # | CPO-9",
                "Logistics Attributes | Bill of Lading # | BOL-123456",
                "Logistics Attributes | PO # | PO-31",
                "Logistics Attributes | Reference # | REF-55",
                "Logistics Attributes | Shipping Date | 09/30/2026",
                "Additional Attributes | Trailer No | TR-88",
            ),
            rows(r),
        )
    }

    @Test fun documentClassDisplayName() {
        val cls = OcrParser.documentClass(fixture("dc_cloud"))
        assertEquals("shipping_label", cls)
        assertEquals("Shipping Label", DocumentFields.documentClass(cls!!))
        assertEquals("Bill of Lading", DocumentFields.documentClass("bill_of_lading"))
        assertEquals("Packing Slip", DocumentFields.documentClass("packing_slip"))
    }
}
