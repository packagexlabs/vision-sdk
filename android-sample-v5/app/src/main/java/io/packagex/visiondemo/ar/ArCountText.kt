package io.packagex.visiondemo.ar

import io.packagex.arcount.Bracket
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.Prompt
import io.packagex.arcount.SectionResult
import io.packagex.arcount.SectionState
import io.packagex.arcount.SectionStatus

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

/** Whether the count is a range (spec 5.4: AMBIGUOUS or TENTATIVE units widen it) */
val Bracket.unresolved: Boolean get() = countHigh > countLow

/** The section's count on its bracket (spec 5.5): "GTIN × N", "N–M ?" while unresolved; just N without a GTIN. */
fun bracketText(b: Bracket): String = when {
    b.unresolved -> "${b.countLow}–${b.countHigh} ?"
    b.gtin != null -> "${b.gtin} × ${b.countLow}"
    else -> "${b.countLow}"
}

/**
 * The buttons the section's state offers, in order: Finish while COUNTING or FROZEN (spec 5.1); Restart from FROZEN or
 * an unresolved count; Accept range while unresolved.
 */
fun arButtons(view: CountView): List<Command> = buildList {
    val unresolved = view.bracket?.unresolved == true
    if (view.state == SectionState.COUNTING || view.state == SectionState.FROZEN) add(Command.Finish)
    if (view.state == SectionState.FROZEN || unresolved) add(Command.Restart)
    if (unresolved) add(Command.AcceptRange)
}

fun buttonLabel(command: Command): String = when (command) {
    Command.Finish -> "Finish"
    Command.Restart -> "Restart"
    Command.AcceptRange -> "Accept range"
    else -> command.toString()
}

/** The count of a closed section: "N", or "N–M" for an unresolved range. */
fun countText(s: SectionResult): String = if (s.countHigh > s.countLow) "${s.countLow}–${s.countHigh}" else "${s.countLow}"

fun statusText(status: SectionStatus): String = when (status) {
    SectionStatus.COMPLETE -> "Complete"
    SectionStatus.UNRESOLVED -> "Unresolved"
    SectionStatus.CLOSED_FROZEN -> "Closed while frozen"
    SectionStatus.ABANDONED -> "Abandoned"
}

/** The hint line for AR Count: the section's state, and the working distance of the session's stream (spec 5.2). */
fun arHint(view: CountView, stream: AppStream?): String {
    val state = when (view.state) {
        SectionState.IDLE -> "No section open"
        SectionState.OPEN -> "Section open"
        SectionState.COUNTING -> "Counting"
        SectionState.FROZEN -> "Count frozen"
        SectionState.CLOSED -> "Section closed"
    }
    return if (stream == null) state else "$state · within ${stream.workingDistanceCm} cm"
}
