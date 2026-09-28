package io.packagex.visiondemo.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState

/**
 * Presents [ScannerUiState.sheet] as a bottom sheet. Ported 1:1 from iOS `UI/Sheets.swift` `SheetHost`.
 *
 * Sheet-swap handshake (owned by [io.packagex.visiondemo.scanner.ScannerViewModel], driven from here):
 * `DismissSheet` sets `state.sheet = null` while this composable is still showing the previous kind's
 * content -- we keep rendering it, animate [androidx.compose.material3.SheetState.hide] to completion,
 * then send [ScannerAction.SheetDismissed] exactly once. The ViewModel then presents `pendingSheet`, if any.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    // Remembers the last non-null sheet kind so its content keeps rendering while the sheet hides.
    var shownKind by remember { mutableStateOf(state.sheet) }
    if (state.sheet != null) shownKind = state.sheet
    val kind = shownKind ?: return

    // A fresh SheetState per kind: detents differ per kind and can't change after creation.
    key(kind) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = kind !in PartialDetentKinds)

        LaunchedEffect(state.sheet) {
            if (state.sheet == null) {
                sheetState.hide()
                shownKind = null
                onAction(ScannerAction.SheetDismissed)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { onAction(ScannerAction.DismissSheet) },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            SheetScaffold(title = titleFor(kind), onClose = { onAction(ScannerAction.DismissSheet) }) {
                when (kind) {
                    SheetKind.Settings -> SettingsSheet(state, onAction)
                    SheetKind.DocType -> DocTypeSheet(state, onAction)
                    SheetKind.Items -> ItemsSheet(state, onAction)
                    SheetKind.Models -> ModelsSheet(state, onAction)
                    // Task 11: AR items sheet body (barcode-catalog naming). Nothing to render yet.
                    SheetKind.ArItems -> {}
                }
            }
        }
    }
}

/** iOS `.medium, .large` detents; everything else is `.large` only. */
private val PartialDetentKinds = setOf(SheetKind.DocType, SheetKind.Items, SheetKind.ArItems)

private fun titleFor(kind: SheetKind): String = when (kind) {
    SheetKind.Settings -> "Settings"
    SheetKind.DocType -> "Document type"
    SheetKind.Items -> "Item list"
    SheetKind.Models -> "On-device models"
    SheetKind.ArItems -> "Items"
}
