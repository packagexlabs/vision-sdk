package io.packagex.visiondemo.ar

import io.packagex.arcount.Bracket
import io.packagex.arcount.Prompt

/** The prompt's wording (spec 5.5); the range one shows the bracket's numbers. */
fun promptText(prompt: Prompt, bracket: Bracket?): String = when (prompt) {
    Prompt.HOLD_STILL_A_MOMENT -> "Hold still a moment"
    Prompt.SCAN_SHELF_LABEL -> "Scan the shelf label"
    Prompt.SCAN_LABEL_TO_CONTINUE -> "Scan the shelf label to continue"
    Prompt.RANGE_RESCAN -> bracket?.let { "Range: ${it.countLow}–${it.countHigh}. Rescan from the label?" } ?: "Rescan from the label?"
    Prompt.TOO_DARK -> "Too dark: hold still and press the trigger"
    Prompt.SLOW_DOWN -> "Slow down"
    Prompt.SLIDE_A_LITTLE -> "Slide a little"
    Prompt.MOVE_CLOSER -> "Move closer"
    Prompt.HOLD_WITHIN_30CM -> "Hold within 30 cm"
    Prompt.DEVICE_HOT -> "Device hot"
    Prompt.REREAD_COUNTED_ITEMS -> "Point at items you counted to continue"
}
