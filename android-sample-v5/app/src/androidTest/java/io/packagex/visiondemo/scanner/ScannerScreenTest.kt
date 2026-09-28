package io.packagex.visiondemo.scanner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.packagex.visiondemo.designsystem.VisionTheme
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
    fun dialHasSevenModes() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(), cameraView = {}) {} } }
        listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "Item retrieval", "AR Barcode", "Document Acquisition")
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Dimensioning").assertDoesNotExist()
    }

    @Test
    fun codeDetectedOnlyWhenInFrame() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(codeInFrame = false), cameraView = {}) {} } }
        rule.onNodeWithText("Code detected", substring = true).assertDoesNotExist()
    }
}
