package io.packagex.arcount

/** A closed section's result for the host (spec 5.7): the count is [Counts.low], its range [Counts.low]..[Counts.high] */
fun sectionResult(
    sectionId: String,
    labelPayload: String?,
    gtins: Set<String>,
    status: SectionStatus,
    counts: Counts,
    manualAdded: Int,
    manualRemoved: Int,
    breaks: List<Pair<Long, BreakReason>>,
    durationMs: Long,
) = SectionResult(
    sectionId = sectionId,
    labelPayload = labelPayload,
    gtins = gtins,
    status = status,
    counted = counts.counted,
    manualAdded = manualAdded,
    manualRemoved = manualRemoved,
    tentative = counts.tentative,
    ambiguous = counts.ambiguous,
    countLow = counts.low,
    countHigh = counts.high,
    breaks = breaks,
    durationMs = durationMs,
)

/**
 * The result as JSON, keys in the order of spec 5.7, GTINs sorted, no library. A break is {"t": camera timestamp in
 * ns, "reason": its name}.
 */
fun SectionResult.toJson(): String = buildString {
    append("{\"sectionId\":").append(quote(sectionId))
    append(",\"labelPayload\":").append(labelPayload?.let { quote(it) } ?: "null")
    append(",\"gtins\":[").append(gtins.sorted().joinToString(",") { quote(it) }).append(']')
    append(",\"status\":").append(quote(status.name))
    append(",\"counted\":").append(counted)
    append(",\"manualAdded\":").append(manualAdded)
    append(",\"manualRemoved\":").append(manualRemoved)
    append(",\"tentative\":").append(tentative)
    append(",\"ambiguous\":").append(ambiguous)
    append(",\"countLow\":").append(countLow)
    append(",\"countHigh\":").append(countHigh)
    append(",\"breaks\":[").append(breaks.joinToString(",") { (t, reason) -> "{\"t\":$t,\"reason\":${quote(reason.name)}}" }).append(']')
    append(",\"durationMs\":").append(durationMs)
    append('}')
}

private fun quote(s: String) = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
