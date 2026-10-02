package io.packagex.visiondemo.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Loading placeholder shaped like a result's fields: a primary card, then label/value rows, each a grey bar with a
 *  light band sweeping across. The sweep is read only while drawing, so it never recomposes. */
@Composable
fun ShimmerRows(modifier: Modifier = Modifier, rows: Int = 4) {
    val sweep = rememberInfiniteTransition(label = "shimmer")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "sweep")
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Bar(sweep, 1f, 72.dp, 16.dp)
        Column {
            repeat(rows) { i ->
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Bar(sweep, 0.3f, 10.dp, 4.dp)
                    Bar(sweep, if (i % 2 == 0) 0.75f else 0.55f, 14.dp, 4.dp)
                }
                HorizontalDivider(color = PX.Hairline)
            }
        }
    }
}

@Composable
private fun Bar(sweep: State<Float>, width: Float, height: Dp, corner: Dp) {
    Spacer(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .drawBehind {
                val band = 160.dp.toPx()
                val x = sweep.value * (size.width + band) - band
                drawRect(Brush.linearGradient(listOf(PX.Hairline, PX.Surface, PX.Hairline), start = Offset(x, 0f), end = Offset(x + band, 0f)))
            },
    )
}
