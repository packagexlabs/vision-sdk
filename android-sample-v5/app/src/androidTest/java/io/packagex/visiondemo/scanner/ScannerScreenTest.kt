package io.packagex.visiondemo.scanner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Task 8 brief's required failing-first UI tests for [ScannerScreen]. */
class ScannerScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun pausedShowsNoDialogAndTapResumes() {
        var got: ScannerAction? = null
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(paused = true), cameraView = {}) { got = it } } }
        rule.onNodeWithText("Resume camera").assertDoesNotExist()
        rule.onNodeWithContentDescription("Camera paused").performClick()
        assertEquals(ScannerAction.Resume, got)
    }

    @Test
    fun homeHasSevenModuleCards() {
        var got: ScannerAction? = null
        rule.setContent { VisionTheme { HomeScreen(ScannerUiState(), onAction = { got = it }) } }
        listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "AR Item Count", "Document Acquisition")
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Dimensioning").assertDoesNotExist()
        rule.onNodeWithText("Price tag").performClick()
        assertEquals(ScannerAction.SetMode(ScanMode.Price), got)
    }

    @Test
    fun cameraBackArrowGoesHome() {
        var got: ScannerAction? = null
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(home = false), cameraView = {}) { got = it } } }
        rule.onNodeWithContentDescription("Back to modules").performClick()
        assertEquals(ScannerAction.GoHome, got)
    }

    @Test
    fun codeDetectedOnlyWhenInFrame() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(codeInFrame = false), cameraView = {}) {} } }
        rule.onNodeWithText("Code detected", substring = true).assertDoesNotExist()
    }

    @Test
    fun codeDetectedShowsWhenInFrame() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(codeInFrame = true), cameraView = {}) {} } }
        rule.onNodeWithText("Code detected", substring = true).assertExists()
    }

    @Test
    fun hintsHiddenWhenPrefOff() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(prefs = Prefs(showHints = false)), cameraView = {}) {} } }
        rule.onNodeWithText("Align the code inside the frame").assertDoesNotExist()
    }

    @Test
    fun detectionOffShowsPausedHint() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(detectionEnabled = false), cameraView = {}) {} } }
        rule.onNodeWithText("Detection paused").assertExists()
    }

    @Test
    fun visionScannerOffersPhotoImport() {
        var got: ScannerAction? = null
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(mode = ScanMode.Ocr), cameraView = {}) { got = it } } }
        rule.onNodeWithContentDescription("Import from Photos").performClick()
        assertEquals(ScannerAction.PickPhoto, got)
    }
}
