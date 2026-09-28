package io.packagex.visiondemo.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.model.OcrResult
import java.net.URI

/**
 * Thumbs up / down / edit controls on an item-label field.
 * Ported from iOS `UI/ResultDrawer.swift`'s `FieldFeedbackButtons`.
 */
@Composable
fun FieldFeedbackButtons(
    entry: ItemLabelFeedback.Entry?,
    editing: Boolean,
    onThumbs: (Boolean?) -> Unit,
    onEdit: () -> Unit,
) {
    val thumbs = entry?.thumbs
    Row {
        FeedbackIcon(
            selected = Icons.Filled.ThumbUp,
            unselected = Icons.Outlined.ThumbUp,
            on = thumbs == true,
            tint = Color(0xFF1B8A63),
            label = "Correct",
        ) { onThumbs(if (thumbs == true) null else true) }
        FeedbackIcon(
            selected = Icons.Filled.ThumbDown,
            unselected = Icons.Outlined.ThumbDown,
            on = thumbs == false,
            tint = PX.RedText,
            label = "Wrong",
        ) { onThumbs(if (thumbs == false) null else false) }
        FeedbackIcon(
            selected = Icons.Filled.Check,
            unselected = Icons.Filled.Edit,
            on = editing,
            tint = PX.Purple,
            label = if (editing) "Done editing" else "Correct value",
        ) { onEdit() }
    }
}

@Composable
private fun FeedbackIcon(
    selected: ImageVector,
    unselected: ImageVector,
    on: Boolean,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (on) selected else unselected, contentDescription = null, tint = if (on) tint else PX.Muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * "Field feedback" section under an item-label result: rate/correct summary, an optional overall
 * comment, and the submit button. Submitting only dispatches [ScannerAction.SendFeedback]; the
 * ViewModel owns the network call and the result toast. Ported from iOS `feedbackSection`.
 */
@Composable
fun ItemLabelFeedbackSection(
    result: OcrResult,
    entries: Map<String, ItemLabelFeedback.Entry>,
    comment: String,
    onCommentChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val rated = entries.values.count { it.thumbs != null }
    val corrected = result.fields.count { f -> entries[f.id]?.let { it.edited != f.value } ?: false }
    val host = runCatching { URI(ItemLabelFeedback.server).host }.getOrNull() ?: "feedback server"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SectionLabel(text = "Field feedback")
        Text(
            "Rate fields with thumbs up / down and tap the pencil to correct a value. $rated rated · $corrected corrected.",
            style = inter(12.sp), color = PX.Muted,
        )
        Text(
            "Sending uploads this image and its fields to the feedback service ($host) for model improvement.",
            style = inter(11.sp), color = PX.Muted,
        )
        OutlinedTextField(
            value = comment,
            onValueChange = onCommentChange,
            placeholder = { Text("Overall feedback (optional)", style = inter(14.sp)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PX.Purple, unfocusedBorderColor = Color(0xFFD9D0E3)),
        )
        PXButton(title = "Send feedback", kind = PXButtonKind.Secondary, height = 44.dp, onClick = onSubmit)
    }
}
