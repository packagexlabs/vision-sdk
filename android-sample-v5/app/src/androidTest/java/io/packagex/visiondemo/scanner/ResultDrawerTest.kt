package io.packagex.visiondemo.scanner

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.packagex.visiondemo.data.OcrParser
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ScanResult
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** From task-10-brief.md's Step 1, adapted to the real `ResultDrawer`/`ReportCard` signatures. */
class ResultDrawerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun ocrPrimaryAndFieldsShown() {
        val r = OcrParser.parse("""{"data":{"inference":{"tracking_number":"1Z9","provider_name":"UPS"}}}""", DocType.SL)
        rule.setContent { VisionTheme { ResultDrawer(ScanResult.Ocr(r, null)) {} } }
        // iOS :183-238 shows the primary field both in its own large card and again in the field
        // list below, so "1Z9" (the primary tracking number) is expected twice.
        rule.onAllNodesWithText("1Z9").assertCountEquals(2)
        rule.onNodeWithText("Courier").assertExists()
    }

    @Test fun reportNeedsAField() {
        var got: ScannerAction? = null
        val r = OcrParser.parse("""{"data":{"inference":{"tracking_number":"1Z9"}}}""", DocType.SL)
        rule.setContent { VisionTheme { ReportCard(r, onAction = { got = it }, onClose = {}) } }
        rule.onNodeWithText("Submit").performClick()
        assertNull(got)
        rule.onNodeWithText("Select at least one field").assertExists()
    }

    @Test fun priceDrawerShowsTagsFromState() {
        val tag = io.packagex.visiondemo.data.PriceTag.from("14438-1", "$28.99")
        rule.setContent { VisionTheme { ResultDrawer(ScanResult.Price, tags = listOf(tag)) {} } }
        rule.onNodeWithText("Found 1 Items").assertExists()
        rule.onNodeWithText("14438").assertExists()
    }

    @Test fun codesDrawerShowsValueAndSymbology() {
        val code = io.packagex.visiondemo.model.DetectedCode(
            value = "1234567890",
            symbology = "UPC-A",
            box = io.packagex.visiondemo.model.Box(0, 0, 10, 10),
        )
        rule.setContent { VisionTheme { ResultDrawer(ScanResult.Codes(listOf(code))) {} } }
        rule.onNodeWithText("1234567890").assertExists()
        // The header subtitle and the dark card's label both show the symbology (iOS :99's
        // `c.symbology` appears in the subtitle; the card mirrors it as its label).
        rule.onAllNodesWithText("UPC-A").assertCountEquals(2)
    }
}
