package io.packagex.visiondemo.designsystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import io.packagex.visiondemo.R

/** PackageX design-system type tokens. Ported 1:1 from iOS `UI/Theme.swift`. */

@OptIn(ExperimentalTextApi::class)
private fun variableWeight(weight: FontWeight) = FontVariation.Settings(FontVariation.weight(weight.weight))

@OptIn(ExperimentalTextApi::class)
val Montserrat = FontFamily(
    Font(R.font.montserrat, weight = FontWeight.Normal, variationSettings = variableWeight(FontWeight.Normal)),
    Font(R.font.montserrat, weight = FontWeight.Medium, variationSettings = variableWeight(FontWeight.Medium)),
    Font(R.font.montserrat, weight = FontWeight.SemiBold, variationSettings = variableWeight(FontWeight.SemiBold)),
    Font(R.font.montserrat, weight = FontWeight.Bold, variationSettings = variableWeight(FontWeight.Bold)),
)

@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal, variationSettings = variableWeight(FontWeight.Normal)),
    Font(R.font.inter, weight = FontWeight.Medium, variationSettings = variableWeight(FontWeight.Medium)),
    Font(R.font.inter, weight = FontWeight.SemiBold, variationSettings = variableWeight(FontWeight.SemiBold)),
)

val DmMono = FontFamily(Font(R.font.dm_mono_medium, weight = FontWeight.Medium))


fun montserrat(size: TextUnit, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    TextStyle(fontFamily = Montserrat, fontSize = size, fontWeight = weight)

fun inter(size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = Inter, fontSize = size, fontWeight = weight)

fun mono(size: TextUnit): TextStyle =
    TextStyle(fontFamily = DmMono, fontSize = size, fontWeight = FontWeight.Medium)
