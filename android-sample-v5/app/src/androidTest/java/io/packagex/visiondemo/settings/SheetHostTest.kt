package io.packagex.visiondemo.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState
import org.junit.Rule
import org.junit.Test

/** Ported from `task-9-brief.md` Step 1: presenting Settings, tapping its "Models" row queues+swaps
 *  to the Models sheet via the OpenSheet -> DismissSheet(sheet=null) -> SheetDismissed handshake.
 *  Settings has enough rows that "Models" (the last one) sits below the fold in the sheet's scrollable
 *  content on real device screens -- `performScrollTo()` brings it on-screen first, as `performClick()`
 *  synthesizes a tap at the node's actual (possibly off-screen) position and does not auto-scroll. */
class SheetHostTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun sheetSwapPresentsNext() {
        var state by mutableStateOf(ScannerUiState(sheet = SheetKind.Settings))
        rule.setContent {
            VisionTheme {
                SheetHost(state) { a ->
                    state = when (a) {
                        is ScannerAction.OpenSheet -> state.copy(sheet = null, pendingSheet = a.k)
                        ScannerAction.SheetDismissed -> state.copy(sheet = state.pendingSheet, pendingSheet = null)
                        else -> state
                    }
                }
            }
        }
        rule.onNodeWithText("Models").performScrollTo().performClick() // row in Settings that opens the Models sheet
        rule.waitUntil(3_000) { rule.onAllNodesWithText("On-device models").fetchSemanticsNodes().isNotEmpty() }
    }
}
