package io.packagex.visiondemo.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Presents [ScannerUiState.sheet] as a bottom sheet. Ported 1:1 from iOS `UI/Sheets.swift` `SheetHost`.
 *
 * Sheet-swap handshake (owned by [io.packagex.visiondemo.scanner.ScannerViewModel], driven from here):
 * `DismissSheet` sets `state.sheet = null` while this composable is still showing the previous kind's
 * content -- we keep rendering it, animate [androidx.compose.material3.SheetState.hide] to completion,
 * then send [ScannerAction.SheetDismissed] exactly once. The ViewModel then presents `pendingSheet`, if any.
 *
 * The hide-then-notify [LaunchedEffect] below is deliberately kept OUTSIDE any `key(kind)` block and
 * keyed only on `state.sheet` (never on `shownKind`, which it itself clears at the end). An earlier
 * version nulled `shownKind` -- which gates `key(kind)`, i.e. its own enclosing scope -- *before* sending
 * [ScannerAction.SheetDismissed], from inside a `key(kind)`-scoped effect: self-invalidating like that is
 * exactly the kind of race that can tear an in-flight coroutine down before it finishes notifying, so it's
 * restructured here so nothing this effect does can invalidate the composition it runs in, even though a
 * real-device repro (see `SheetHostTest`, fix round 2) pinned the actual failure on a different cause: the
 * "Models" row sat below the fold in Settings' scrollable content, so the test's `performClick()` (which
 * taps a node's real, possibly off-screen position and does not auto-scroll) never reached it and
 * `OpenSheet` was never sent at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    RecomposeLog("SheetHost")
    // Remembers the last non-null sheet kind so its content keeps rendering while the sheet hides.
    var shownKind by remember { mutableStateOf(state.sheet) }
    if (state.sheet != null) shownKind = state.sheet
    val kind = shownKind ?: return

    // A fresh SheetState per kind: detents differ per kind and can't change after creation.
    val sheetState = key(kind) { rememberModalBottomSheetState(skipPartiallyExpanded = kind !in PartialDetentKinds) }

    // A half-height sheet keeps its lower half (where the Items / AR items text fields are) off screen, so the
    // keyboard would cover a focused field: while the keyboard is up, the sheet never settles at half height
    // (opening the keyboard expands it, and so does dragging it back down to half with the keyboard still up).
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(sheetState) {
        snapshotFlow { ime.getBottom(density) > 0 && sheetState.targetValue == SheetValue.PartiallyExpanded }
            .distinctUntilChanged()
            .collect { if (it) sheetState.expand() }
    }

    LaunchedEffect(state.sheet) {
        if (state.sheet == null) {
            sheetState.hide()
            onAction(ScannerAction.SheetDismissed)
            shownKind = null
        }
    }

    key(kind) {
        ModalBottomSheet(
            onDismissRequest = { onAction(ScannerAction.DismissSheet) },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            // White all the way up, drag handle strip included: the default container is the theme's tinted
            // surfaceContainerLow, which shows as a lilac band behind the status bar when the sheet is fully up.
            containerColor = Color.White,
            tonalElevation = 0.dp,
        ) {
            SheetScaffold(title = titleFor(kind), onClose = { onAction(ScannerAction.DismissSheet) }) {
                when (kind) {
                    SheetKind.Settings -> SettingsSheet(state, onAction)
                    SheetKind.DocType -> DocTypeSheet(state, onAction)
                    SheetKind.Items -> ItemsSheet(state, onAction)
                    SheetKind.Models -> ModelsSheet(state, onAction)
                    SheetKind.ArItems -> ArItemsSheet(state, onAction)
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
