package io.packagex.visiondemo.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** PackageX design-system components. Ported 1:1 from iOS `UI/Theme.swift`. */

enum class PXButtonKind { Primary, Secondary, Tertiary }

@Composable
fun PXButton(
    title: String,
    kind: PXButtonKind = PXButtonKind.Primary,
    height: Dp = 54.dp,
    onClick: () -> Unit,
) {
    val contentColor = if (kind == PXButtonKind.Primary) Color.White else PX.Purple
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (kind == PXButtonKind.Primary) PX.Purple else Color.Transparent,
            contentColor = contentColor,
        ),
        border = if (kind == PXButtonKind.Secondary) BorderStroke(1.dp, PX.Purple) else null,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Text(title, style = inter(16.sp, FontWeight.Medium), textAlign = TextAlign.Center)
    }
}

enum class BadgeTone { Success, Brand, Neutral, Danger }

private fun BadgeTone.colors(): Pair<Color, Color> = when (this) {
    BadgeTone.Success -> Color(0xFFD1FCEC) to Color(0xFF1B8A63)
    BadgeTone.Brand -> Color(0xFFEFE5FC) to PX.Purple
    BadgeTone.Neutral -> PX.Surface to PX.Text2
    BadgeTone.Danger -> Color(0xFFF4D4D4) to PX.RedText
}

@Composable
fun Badge(text: String, tone: BadgeTone = BadgeTone.Neutral, dot: Boolean = false) {
    val (bg, fg) = tone.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .heightIn(min = 22.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, style = montserrat(11.sp), color = fg, maxLines = 1)
    }
}

@Composable
fun <T> Segmented(
    items: List<Pair<String, T>>,
    selection: T,
    onSelect: (T) -> Unit,
    disabled: Set<T> = emptySet(),
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PX.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, PX.Hairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        items.forEach { (label, value) ->
            val on = selection == value
            val isDisabled = disabled.contains(value)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) PX.Ink else Color.Transparent)
                    .alpha(if (isDisabled) 0.35f else 1f)
                    .clickable(enabled = !isDisabled) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (mono) mono(13.sp) else montserrat(12.sp),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = if (on) PX.Neon else PX.Ink,
                )
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = montserrat(14.sp), color = PX.Ink)
                Text(desc, style = inter(13.sp), color = PX.Muted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = PX.Purple, checkedThumbColor = Color.White),
            )
        }
        HorizontalDivider(color = PX.Hairline)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = montserrat(13.sp), color = PX.Ink)
}

@Composable
fun LinkLabel(text: String, color: Color = PX.Purple, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = montserrat(12.sp), color = color, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(PX.Surface)
                .border(1.dp, PX.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = PX.Ink, modifier = Modifier.size(14.dp))
        }
    }
}

/** Frosted dark circle/pill used by the camera chrome. Translucent fill only — no blur (blur would blur content). */
fun Modifier.glass(radius: Dp = 22.dp, fill: Color = PX.Glass): Modifier =
    this.clip(RoundedCornerShape(radius)).background(fill)
