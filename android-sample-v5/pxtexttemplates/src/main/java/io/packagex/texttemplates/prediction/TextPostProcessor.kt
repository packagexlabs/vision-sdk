package io.packagex.texttemplates.prediction

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Spatial-proximity gate used to decide whether a barcode is "associated"
// with a predicted field. Kept distinct from the bonus magnitude below.
internal const val BARCODE_BOOST_DIST_THRESHOLD = 10.0

// Max length-normalised confusable distance for snapping a barcode-associated
// field's OCR value onto the (AI-prefix-stripped) barcode payload. The geometry
// gate above is the strong association signal; this is a text sanity check, so
// it can be relatively loose — ~1 hard error or 3 confusable swaps per 3 chars.
internal const val BARCODE_SNAP_NORM_THRESHOLD = 0.34

// --- Rank-decay confidence model (iOS-only divergence from Python) ---
//
// Replaces the prior post-hoc additive boost stack with a per-signal
// rank-decay schedule.
//
// For each prediction we look up the candidate's rank in each geo signal's
// per-field candidate list (rank 0 = best match for that signal, rank -1 =
// candidate not present in that signal's list). The per-signal weights are
// summed into `geo_sum`; the text-shape rank weight becomes `text_score`.
// Final confidence is `0.7·geo_sum + 0.3·text_score`, with +0.10 if a
// barcode association is detected, capped at 1.0.
//
// Weight schedules: index = rank. Out-of-range ranks (including the -1
// "not present" sentinel) contribute 0.
internal val KV_RANK_WEIGHTS = doubleArrayOf(0.45, 0.40, 0.35, 0.30, 0.25, 0.20, 0.15, 0.10, 0.05)
internal val BARCODE_RANK_WEIGHTS = doubleArrayOf(0.30, 0.27, 0.24, 0.21, 0.18, 0.15, 0.12, 0.09, 0.06, 0.03)
internal val PAIRWISE_RANK_WEIGHTS = doubleArrayOf(0.125, 0.1125, 0.1, 0.0875, 0.075, 0.0625, 0.05, 0.0375, 0.025, 0.0125)
internal val ANCHOR_RANK_WEIGHTS = PAIRWISE_RANK_WEIGHTS
internal val TEXT_RANK_WEIGHTS = doubleArrayOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1)

internal const val GEO_BLEND_WEIGHT = 0.7
internal const val TEXT_BLEND_WEIGHT = 0.3
internal const val BARCODE_ASSOCIATION_BONUS = 0.10

internal fun rankWeight(table: DoubleArray, rank: Int): Double {
    if (rank < 0 || rank >= table.size) return 0.0
    return table[rank]
}

// Regex patterns for dataType inference
private val NUMERIC_RE = Regex("^[\\d.,\\s\\-+]+$")
private val DATE_RE = Regex(
    "(\\d{1,2}[/\\-.](\\d{1,2})[/\\-.]\\d{2,4})" +
            "|(\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2})" +
            "|(\\w+ \\d{1,2},?\\s*\\d{4})" +
            "|(\\d{1,2}\\s+\\w+\\s+\\d{4})"
)
private val ALPHANUMERIC_RE = Regex("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d\\s\\-_.]+$")

// --- Key-residue stripping ---

/**
 * Punctuation that's never a legitimate first character of a field value
 * but commonly appears glued to one when OCR splits a "KEY:VALUE" pair
 * at the wrong point (e.g. ":JK377", ",750-174928", "|11"). Leading dashes,
 * dots, and parentheses are intentionally NOT stripped — valid prefixes for
 * real values like "-12.5" or "(A)QTY".
 *
 * The non-whitespace chars mirror `OcrExtractor.KV_SEPARATORS` — keep the
 * two in lockstep: a separator the extractor splits glued tokens on is
 * exactly the residue char this strip must recognise.
 */
private val LEADING_KEY_RESIDUE_CHARS = setOf(' ', '\t', '\n', '\r', ':', ';', '|', ',')

internal fun stripLeadingKeyResidue(text: String): String {
    if (text.isEmpty()) return text
    var i = 0
    while (i < text.length && text[i] in LEADING_KEY_RESIDUE_CHARS) i++
    return if (i == 0) text else text.substring(i)
}

// --- DataType inference ---

internal fun inferDatatype(value: String): String {
    val v = value.trim()
    if (v.isEmpty()) return "string"
    if (DATE_RE.containsMatchIn(v)) return "date"
    if (NUMERIC_RE.matches(v)) return "numeric"
    if (ALPHANUMERIC_RE.matches(v)) return "alphanumeric"
    return "string"
}

internal fun matchesDatatype(text: String, datatype: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    return when (datatype) {
        "numeric" -> NUMERIC_RE.matches(t)
        "date" -> DATE_RE.containsMatchIn(t)
        "alphanumeric" -> ALPHANUMERIC_RE.matches(t)
        else -> true // "string" matches everything
    }
}

// --- Text fingerprinting ---

/** Punctuation classes for [toPattern]. Connectors are the glue that appears
 *  INSIDE label values (part numbers, dates, fractions, quantities:
 *  "SM-X-NIM-ADPTR=", "09-OCT-25", "207/500", "1,000"); grouping them into
 *  one class makes OCR's most common punctuation confusions ('='↔'-',
 *  '.'↔',', '_'↔'-') free, while still costing a substitution against the
 *  other two groups. Key markers (':', '#') terminate printed keys and
 *  delimit MAC/time-like values. Everything else ('(', ')', '"', '|', ';',
 *  …) stays 'P': wrappers and OCR junk — '|' deliberately lands there, it's
 *  a misread of I/l/1 or a barcode edge, not a value connector. The K class
 *  reads [keyValueSeparators] — the single definition of "key marker"
 *  characters, shared with [isKeyShaped], `hasKeyMarker`, and
 *  [stripKeyValuePrefix] so the mechanisms can never disagree. Mirrors
 *  `_PATTERN_CONNECTORS` / `_KEY_MARKERS` in api/pipeline.py. */
private val patternConnectors = setOf('-', '_', '/', '.', '=', '+', ',')

private fun toPattern(text: String): String {
    val classes = StringBuilder()
    for (c in text) {
        classes.append(
            when {
                c.isDigit() -> 'D'
                c.isUpperCase() -> 'U'
                c.isLowerCase() -> 'L'
                c.isWhitespace() -> 'S'
                c in patternConnectors -> 'C'
                c in keyValueSeparators -> 'K'
                else -> 'P'
            }
        )
    }
    return classes.toString().replace(Regex("(.)\\1+"), "$1+")
}

internal data class TextFingerprint(
    val length: Int,
    val digitRatio: Double,
    val alphaRatio: Double,
    // Fraction of the stripped text that is alphanumeric at all. The
    // dominance test in charClassMismatchPenalty needs it: with the
    // alnum-denominator digit/alpha ratios, punctuated tokens like "12.5"
    // or "N/A" read as ratio-1.0 "pure" class, which would widen the hard
    // ±5 penalty's firing domain. Gating dominance on alnumRatio >= 0.8
    // restores the penalty's original domain (oldRatio = newRatio ×
    // alnumRatio) while keeping the undiluted ratios for the weighted
    // distance terms. Mirrors `alnum_ratio` in api/pipeline.py.
    val alnumRatio: Double,
    val upperRatio: Double,
    val hasDash: Boolean,
    val hasSpace: Boolean,
    val numWords: Int,
    val pattern: String,
    // Absolute character-class counts. The ratio fields above are
    // length-normalised and the collapsed `pattern` discards run length — so
    // two strings of the same class but vastly different length (e.g. "PA" vs
    // "PACKING") look identical on every other dimension. These three counts
    // re-introduce magnitude as an explicit signal; see textFingerprintDistance.
    val digitCount: Int,
    val alphaCount: Int,
    val separatorCount: Int,
    // Key-shape signals, read from the RAW token because
    // stripSurroundingNonAlnum deletes the trailing ':'/'#' they depend on.
    // See isKeyShaped / KEY_SHAPE_PENALTY.
    val isKeyShaped: Boolean,
    val hasKeyMarker: Boolean,
)

private val fingerprintSeparators = setOf('-', ':', '.', '/', '_')

/**
 * True when the raw token reads like a printed field key (`P/N:`, `Prod.#:`,
 * `Serial#:`, bare `#:`) rather than a value. Printed keys end in a `:`/`#`
 * cluster — and the strip in [computeTextFingerprint] deletes exactly that
 * evidence, so this must look at the RAW token. Two guards keep values out:
 *  - the marker must be in the trailing punctuation run (a value like
 *    `BAC#200` has its `#` interior);
 *  - no colon before the trailing run — an OCR fragment of a colon-delimited
 *    value (`00:1A:2B:` from a split MAC address) ends in a colon but always
 *    carries earlier ones too, while printed keys never do.
 * Mirrors `_is_key_shaped` in api/pipeline.py.
 */
private fun isKeyShaped(raw: String): Boolean {
    val rt = raw.trimEnd()
    var k = rt.length
    while (k > 0 && !rt[k - 1].isLetterOrDigit()) k--
    val trailing = rt.substring(k)
    if (trailing.none { it in keyValueSeparators }) return false
    return ':' !in rt.substring(0, k)
}

/**
 * Drop leading/trailing chars that aren't letters or digits. OCR routinely
 * emits tokens like `(1P)`, `CPN:`, `-LANTER`, `"value"` — surrounding
 * parens/colons/quotes/dashes are formatting artifacts, not part of the
 * value's actual shape. Stripping them before fingerprinting stops a short
 * artifact like `(1P)` from looking length-similar to a short template value
 * like `8808=` purely because of its wrappers. Inner punctuation (e.g. the
 * `-` in `CW9166I-B`) is preserved.
 */
private fun stripSurroundingNonAlnum(text: String): String {
    var i = 0
    var j = text.length
    while (i < j && !text[i].isLetterOrDigit()) i++
    while (j > i && !text[j - 1].isLetterOrDigit()) j--
    return if (i < j) text.substring(i, j) else text
}

// Intentionally narrower than `OcrExtractor.KV_SEPARATORS` / the residue set
// above: `stripKeyValuePrefix` splits at the RIGHTMOST separator, so adding
// `,`/`;`/`|` here would truncate values like "1,000" → "000". Do NOT merge
// the sets. Matches iOS TextPostProcessor.keyValueSeparators.
private val keyValueSeparators = setOf('#', ':')

/**
 * Return the value-side of a `key<sep>value` token, where `<sep>` is `#` or `:`
 * (the glyphs OCR emits when a printed key abuts its value with no whitespace —
 * e.g. `BAC#200`, `P/N: 26470092`). The split is on the rightmost separator so
 * payloads containing an earlier one aren't truncated. Returns the input
 * unchanged when no separator is present. Hyphen/slash are intentionally not
 * included — `SFP-10G-LR` and `10/28/2024` are genuine whole values.
 */
internal fun stripKeyValuePrefix(text: String): String {
    val lastSepIdx = text.indexOfLast { it in keyValueSeparators }
    if (lastSepIdx < 0) return text
    return text.substring(lastSepIdx + 1).trim()
}

/**
 * Generate whitespace-bounded prefix (drop from right) and suffix (drop from
 * left) variants of an expanded text, for the text reranker to score against
 * the template value. Lets the rerank pick a truncation whose token count +
 * fingerprint match the template better than the full multi-word expansion.
 *
 * Returns 2·(N−1) variants; skips when [text] has fewer than 2 tokens. The full
 * input is never included — callers already have it.
 */
internal fun expansionTruncations(text: String): List<String> {
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.size < 2) return emptyList()
    val out = ArrayList<String>(2 * (tokens.size - 1))
    for (k in 1 until tokens.size) out.add(tokens.subList(0, k).joinToString(" "))
    for (k in 1 until tokens.size) out.add(tokens.subList(k, tokens.size).joinToString(" "))
    return out
}

internal fun computeTextFingerprint(rawText: String): TextFingerprint {
    val text = stripSurroundingNonAlnum(rawText)
    val n = max(text.length, 1)
    val digits = text.count { it.isDigit() }
    val alphas = text.count { it.isLetter() }
    val uppers = text.count { it.isUpperCase() }
    val separators = text.count { it in fingerprintSeparators }
    // Digit/alpha ratios use the *alphanumeric* count as denominator, not
    // the full stripped length: interior punctuation otherwise dilutes the
    // dominant class below the 0.8 threshold of charClassMismatchPenalty —
    // "P/N" came out alphaRatio 2/3 and dodged the pure-alpha-vs-pure-digit
    // penalty against template "8808".
    val alnum = max(digits + alphas, 1)
    // Match Python's re.split("\\s+", "") behaviour: empty string → [""], size 1.
    val numWords = if (text.isEmpty()) 1 else text.split(Regex("\\s+")).size

    return TextFingerprint(
        length = text.length,
        digitRatio = digits.toDouble() / alnum,
        alphaRatio = alphas.toDouble() / alnum,
        alnumRatio = (digits + alphas).toDouble() / n,
        upperRatio = uppers.toDouble() / n,
        hasDash = '-' in text,
        hasSpace = ' ' in text,
        numWords = numWords,
        pattern = toPattern(text),
        digitCount = digits,
        alphaCount = alphas,
        separatorCount = separators,
        isKeyShaped = isKeyShaped(rawText),
        hasKeyMarker = rawText.any { it in keyValueSeparators },
    )
}

private fun patternDistance(p1: String, p2: String): Int = levenshtein(p1, p2)

/**
 * Hard penalty added when template and candidate are in opposite character
 * classes (pure-digit vs pure-alpha).
 */
private const val CHAR_CLASS_MISMATCH_PENALTY = 5.0

/**
 * Penalty added when the candidate is key-shaped (trailing `:`/`#`, no
 * interior colon — see [isKeyShaped]) but the template value carries no
 * key-marker character at all. A printed key sitting next to the true value
 * is geometrically close and, when the template's stored exemplar is short,
 * often shape-close too (the Part Number = "P/N:" vs template "8808"
 * failure). Asymmetric on purpose: only the candidate side is tested, and a
 * template value containing `:`/`#` anywhere (MAC-like fields) suppresses
 * the rule for that field entirely. Sized just below
 * [CHAR_CLASS_MISMATCH_PENALTY] — strong enough to push a key token out of
 * the top text rank, weak enough that a geometrically dominant candidate
 * can still survive it. Mirrors `_KEY_SHAPE_PENALTY` in api/pipeline.py.
 */
private const val KEY_SHAPE_PENALTY = 4.0

/**
 * Returns [CHAR_CLASS_MISMATCH_PENALTY] when one fingerprint is predominantly
 * digits and the other has zero digits (or vice versa for alphas). Catches
 * the case where a short label word like `"Part"` coincidentally
 * length-matches a pure-numeric template like `"8808"`. Threshold 0.8 keeps
 * the rule tolerant of templates with a single trailing punctuation char
 * (e.g. `"8808="` → digit_ratio = 0.8).
 */
private fun charClassMismatchPenalty(a: TextFingerprint, b: TextFingerprint): Double {
    // Dominance requires the token to be >= 80% alphanumeric overall too —
    // mixed alnum-punctuation tokens ("12.5", "1/2", "N/A") aren't
    // categorically anything and get no hard penalty either way. See the
    // alnumRatio field doc; mirrors api/pipeline.py.
    val digitDomA = a.digitRatio >= 0.8 && a.alphaRatio == 0.0 && a.alnumRatio >= 0.8
    val digitDomB = b.digitRatio >= 0.8 && b.alphaRatio == 0.0 && b.alnumRatio >= 0.8
    val alphaDomA = a.alphaRatio >= 0.8 && a.digitRatio == 0.0 && a.alnumRatio >= 0.8
    val alphaDomB = b.alphaRatio >= 0.8 && b.digitRatio == 0.0 && b.alnumRatio >= 0.8
    return if ((digitDomA && alphaDomB) || (alphaDomA && digitDomB)) CHAR_CLASS_MISMATCH_PENALTY else 0.0
}

internal fun textFingerprintDistance(a: TextFingerprint, b: TextFingerprint): Double {
    var d = 0.0
    d += abs(ln(max(a.length, 1).toDouble()) - ln(max(b.length, 1).toDouble())) * 3.0
    d += abs(a.digitRatio - b.digitRatio) * 2.0
    d += abs(a.alphaRatio - b.alphaRatio) * 2.0
    d += abs(a.upperRatio - b.upperRatio) * 1.0
    d += if (a.hasDash != b.hasDash) 1.0 else 0.0
    d += if (a.hasSpace != b.hasSpace) 1.0 else 0.0
    d += abs(a.numWords - b.numWords) * 1.5
    // Pattern Levenshtein normalised by the longer pattern's length so raw
    // edit count doesn't unfairly favour short generic patterns over long
    // rich ones.
    val maxPatLen = max(max(a.pattern.length, b.pattern.length), 1)
    d += patternDistance(a.pattern, b.pattern).toDouble() / maxPatLen * 2.0
    // Absolute character-class magnitude. Ratios + `pattern` are
    // scale-invariant, so two strings of the same class but different length
    // collapse to the same point on every other dimension (e.g. "PA" vs
    // "PACKING"). Manhattan distance on raw counts is the missing magnitude
    // signal. Per-class weights are ≈ half the ratio weight above so this
    // dimension complements rather than double-counts ratios.
    d += abs(a.digitCount - b.digitCount) * 0.5
    d += abs(a.alphaCount - b.alphaCount) * 0.5
    d += abs(a.separatorCount - b.separatorCount) * 0.3
    d += charClassMismatchPenalty(a, b)
    // Key-shape penalty — `a` is the template fingerprint, `b` the candidate
    // (all call sites pass template first). See KEY_SHAPE_PENALTY.
    if (b.isKeyShaped && !a.hasKeyMarker) d += KEY_SHAPE_PENALTY
    return d
}

// --- Adaptive text/geo weighting ---

private fun computeSpecificity(templateValue: String): Double {
    val s = templateValue.trim()
    if (s.isEmpty()) return 0.0
    val n = s.length
    var hasDigit = false
    var hasAlpha = false
    var hasSep = false
    val sepChars = setOf('-', ':', '.', '/', '_')
    for (c in s) {
        if (c.isDigit()) hasDigit = true
        else if (c.isLetter()) hasAlpha = true
        if (c in sepChars) hasSep = true
    }
    val lengthF = min(n / 12.0, 1.0)
    val sepBonus2 = if (hasSep) 0.2 else 0.0
    val sepBonus1 = if (hasSep) 0.1 else 0.0

    val score = when {
        hasDigit && hasAlpha -> 0.5 * lengthF + 0.4 + sepBonus1
        hasDigit -> 0.4 + 0.3 * lengthF + sepBonus2
        hasAlpha -> 0.2 * lengthF + sepBonus1
        else -> 0.0
    }
    return min(score, 1.0)
}

private const val TEXT_WEIGHT_SPEC_MULTIPLIER = 0.75
private const val TEXT_WEIGHT_MIN = 0.25
private const val TEXT_WEIGHT_MAX = 0.75

/** Outlier-clip threshold (in MADs above the median) for text-distance
 *  rank-normalization. A lone over-expansion candidate with a wild txtDist
 *  otherwise stretches the [min,max] range and collapses the meaningful spread.
 *  k=3 ≈ standard robust-outlier cutoff; sits above max when there's no outlier
 *  (so it's a no-op for well-behaved candidate sets). */
private const val TEXT_DIST_OUTLIER_MAD_K = 3.0

/** Geo-dominance guard for the text-shape rerank. The geometric winner is
 *  decisive — text-shape may reorder the also-rans but must NOT override the
 *  winner — when its normalized RRF score is at least [GEO_DOMINANCE_RRF_MIN]
 *  and EITHER it leads the best distinct-word runner-up by at least
 *  [GEO_DOMINANCE_RRF_GAP], OR at least [GEO_DOMINANCE_MIN_VOTES] independent
 *  matchers voted for it. The vote arm exists because the RRF gap is rarely
 *  reachable when many candidates split the score mass: the Cisco Part Number
 *  leader ("SM-X-NIM-ADPTR", 2 votes, rrf 0.698) led by only 0.137 and got
 *  overridden by the key token "P/N:". Multi-matcher agreement is direct
 *  evidence that geometry is right, independent of how the rest of the pool
 *  divides the remainder. Without the guard, a short distractor beats the
 *  true value when the template's stored value is a non-representative
 *  exemplar of a variable field (the Cisco "Product Number" = "8808=" case).
 *  Thresholds are deliberately conservative so ambiguous-geometry cases —
 *  where text-shape is the intended tie-breaker — are untouched. */
private const val GEO_DOMINANCE_RRF_MIN = 0.6
private const val GEO_DOMINANCE_RRF_GAP = 0.4
private const val GEO_DOMINANCE_MIN_VOTES = 2
// The vote-count path to dominance requires a clear RRF separation from the
// runner-up: a leader with enough votes but a near-tie RRF (e.g. leader 0.945
// vs runner-up 0.793 — the Postal Code "TORONTO" over "M5J 2Y1" case) is NOT
// decisive. Letting the text-shape rerank break it lets a perfect-shape value
// (a postal code matching the template's postal code, txtDist≈0) win over a
// geometrically-popular distractor. Raised from 0.15 → 0.25 and the
// "strictly out-votes the runner-up" shortcut removed, so a vote-count tie no
// longer protects a near-tie leader.
private const val GEO_DOMINANCE_VOTES_MIN_GAP = 0.25

/** Per-field text weight, clamped to [min, max] (defaults [0.25, 0.75]). The
 *  multiplier and clamp bounds default to the module constants but can be
 *  overridden per-template via [rerankWeights] (keys text_weight_multiplier /
 *  text_weight_min / text_weight_max). Mirrors `_adaptive_text_weight` in
 *  api/pipeline.py — keep in lockstep. */
private fun adaptiveTextWeight(
    templateValue: String,
    rerankWeights: Map<String, Double>? = null,
): Double {
    val mult = rerankWeights?.get("text_weight_multiplier") ?: TEXT_WEIGHT_SPEC_MULTIPLIER
    val lo = rerankWeights?.get("text_weight_min") ?: TEXT_WEIGHT_MIN
    val hi = rerankWeights?.get("text_weight_max") ?: TEXT_WEIGHT_MAX
    return max(lo, min(hi, computeSpecificity(templateValue) * mult))
}

private const val SPECIFICITY_DEFICIT_SCALE = 5.0
private const val LENGTH_OVERSHOOT_MULTIPLIER = 2
private const val LENGTH_OVERSHOOT_SCALE = 1.5

/**
 * Asymmetric specificity penalty: candidates *less* specific than the template
 * are pushed away from it in text-shape distance; equally- or more-specific
 * candidates get 0.
 *
 * Adds a length-overshoot term for the opposite case: a candidate
 * substantially longer than the template (e.g. an 11-char invoice number
 * scored against a 4-char state-code template). Without it, an over-specific
 * noise candidate sharing the template's char-class can land closer than a
 * short, less-specific but type-appropriate match (e.g. "PA" losing to
 * "SOMA0177931" against "NJ-1").
 */
private fun specificityDeficitPenalty(templateValue: String, candidateValue: String): Double {
    val template = stripSurroundingNonAlnum(templateValue)
    val candidate = stripSurroundingNonAlnum(candidateValue)
    val tSpec = computeSpecificity(template)
    val cSpec = computeSpecificity(candidate)
    val underSpecificity = max(0.0, tSpec - cSpec) * SPECIFICITY_DEFICIT_SCALE

    val tLen = max(template.length, 1)
    val cLen = max(candidate.length, 1)
    var lengthOvershoot = 0.0
    if (cLen > tLen * LENGTH_OVERSHOOT_MULTIPLIER) {
        lengthOvershoot = (cLen - tLen * LENGTH_OVERSHOOT_MULTIPLIER) * LENGTH_OVERSHOOT_SCALE
    }
    return underSpecificity + lengthOvershoot
}

// --- Rerank with text shape ---

internal fun rerankWithTextShape(
    predictions: MutableMap<String, MutableMap<String, Any?>>,
    suggestionsMap: MutableMap<String, List<MutableMap<String, Any?>>>,
    fieldTexts: Map<String, String>,
    rerankWeights: Map<String, Double>? = null,
    fieldBarcodeValues: Map<String, String> = emptyMap(),
    disableGeoDominance: Boolean = false,
    debugLog: MutableList<String>? = null,
) {
    for (field in predictions.keys.toList()) {
        val pred = predictions[field] ?: continue
        val suggs = suggestionsMap[field] ?: emptyList()
        val templateValue = fieldTexts[field] ?: ""

        if (templateValue.isEmpty() || suggs.isEmpty()) {
            debugLog?.add("[$field] skip: templateValue=${templateValue.ifEmpty { "(empty)" }}, suggs=${suggs.size}")
            continue
        }

        val candidates = mutableListOf(pred)
        candidates.addAll(suggs.filter { it["text"]?.toString()?.isNotEmpty() == true })
        if (candidates.size < 2) continue

        val templateFp = computeTextFingerprint(templateValue)
        // Barcode-sourced candidates (injected barcode payloads) are scored
        // against the template's BARCODE value, not the OCR text value: a live
        // payload "123045" must compete barcode-vs-barcode with the template's
        // "123045", not be penalised against the spaced/OCR'd text "123 045".
        // Text-sourced candidates keep the text reference. Same-source scoring.
        val barcodeValue = fieldBarcodeValues[field]?.takeIf { it.isNotEmpty() }
        val barcodeFp = barcodeValue?.let { computeTextFingerprint(it) }
        // Field-level text weight stays keyed on the text value (the field's
        // primary representation); barcode and text values have near-identical
        // specificity, and both distances live on the same 0=perfect scale, so
        // pooling source-routed distances is valid.
        val textWeight = adaptiveTextWeight(templateValue, rerankWeights)
        val geoWeight = 1.0 - textWeight
        debugLog?.add(
            "[$field] templateValue=\"$templateValue\""
                    + (barcodeValue?.let { ", barcodeValue=\"$it\"" } ?: "")
                    + ", candidates=${candidates.size}, textWeight=${"%.3f".format(textWeight)}"
        )

        val textDists = candidates.map { c ->
            val text = c["text"]?.toString() ?: ""
            val fromBarcode = (c["barcode_suggestion"] as? Boolean) == true && barcodeFp != null
            val refFp = if (fromBarcode) barcodeFp!! else templateFp
            val refValue = if (fromBarcode) barcodeValue!! else templateValue
            textFingerprintDistance(refFp, computeTextFingerprint(text)) +
                    specificityDeficitPenalty(refValue, text)
        }

        // Clip text-distance outliers before rank-normalizing. A single bogus
        // over-expansion (e.g. "Packaging Material: LOOSPC" scored against a
        // "66 lb" template → txtDist ~47, inflated by the length-overshoot
        // penalty) otherwise stretches the [min, max] range and compresses the
        // meaningful spread — so a poor-text candidate's normalized term
        // collapses toward 0 and it beats a near-perfect match sitting a couple
        // of geo-ranks down (the Weight "3 lb" vs "Packaging" failure). Clip to
        // a robust ceiling (median + k·MAD) so a lone outlier can't dominate the
        // normalization. No-op when there's no outlier (ceiling sits above max).
        val sortedDists = textDists.sorted()
        val medT = sortedDists[sortedDists.size / 2]
        val madT = run {
            val devs = textDists.map { abs(it - medT) }.sorted()
            val m = devs[devs.size / 2]
            if (m > 1e-6) m else 1.0
        }
        val ceilT = medT + TEXT_DIST_OUTLIER_MAD_K * madT
        val clippedDists = textDists.map { min(it, ceilT) }

        // Magnitude-aware text term: scale clipped text_dists into
        // [0, N_distinct - 1]. Using the count of *distinct* text_dist values
        // (rather than the raw candidate count) keeps the text dim's spend room
        // proportional to its information content — duplicate-text candidates at
        // different geo_ranks don't inflate the range.
        val distinctCount = clippedDists.toSet().size
        val nMinusOne = max(distinctCount - 1, 1).toDouble()
        val minT = clippedDists.min()
        val maxT = clippedDists.max()
        val denom = max(maxT - minT, 1e-9)
        val textTerms = clippedDists.map { (it - minT) / denom * nMinusOne }

        data class Combined(val score: Double, val textDist: Double, val index: Int, val candidate: MutableMap<String, Any?>)

        val combined = candidates.mapIndexed { i, c ->
            val geoR = (c["geo_rank"] as? Number)?.toInt() ?: i
            val score = geoWeight * geoR + textWeight * textTerms[i]
            debugLog?.add(
                "  \"${c["text"]}\": geoR=$geoR, txtTerm=${"%.3f".format(textTerms[i])}, "
                        + "txtDist=${"%.3f".format(textDists[i])}, score=${"%.3f".format(score)}"
            )
            // Stamp the text-shape distance onto each candidate for dedupByTextScore.
            c["text_dist"] = textDists[i]
            Combined(score, textDists[i], i, c)
        }.sortedWith(compareBy({ it.score }, { it.textDist }))

        val reranked = combined.map { it.candidate }

        // Geo-dominance guard: if the geometric winner (pred, geo_rank 0) is
        // decisive, keep it as the prediction regardless of text-shape — only
        // the also-rans get reordered. See GEO_DOMINANCE_RRF_MIN/GAP.
        val leaderRrf = (pred["rrf_score"] as? Number)?.toDouble() ?: 0.0
        val leaderWi = (pred["word_index"] as? Number)?.toInt()
        // The strongest competitor on a DIFFERENT word — take its rrf AND votes
        // from the same candidate so the vote comparison below is apples-to-apples.
        val runnerUp = candidates.drop(1)
            .filter { (it["word_index"] as? Number)?.toInt() != leaderWi }
            .maxByOrNull { (it["rrf_score"] as? Number)?.toDouble() ?: 0.0 }
        val runnerUpRrf = (runnerUp?.get("rrf_score") as? Number)?.toDouble() ?: 0.0
        val runnerUpVotes = (runnerUp?.get("n_votes") as? Number)?.toInt() ?: 0
        val leaderVotes = (pred["n_votes"] as? Number)?.toInt() ?: 0
        // A key-shaped leader gets no geo-dominance protection (unless the
        // template value itself carries a key marker): printed keys sit right
        // next to their values, so geometric matchers — kv especially —
        // routinely agree on them. Multi-matcher consensus on a key token is
        // an adjacency artifact, not evidence; let the text-shape rerank
        // (which penalizes key-shaped candidates) demote it. The Cisco
        // Package Id leader "#:" (2 votes, rrf 0.658) is the motivating case.
        val leaderProtectable = !(isKeyShaped(pred["text"]?.toString() ?: "") &&
                !templateFp.hasKeyMarker)
        val rrfGap = leaderRrf - runnerUpRrf
        // `disableGeoDominance` (per-template flag) forces geometry to never
        // lock the top-1, so the text-shape reranker's winner is always taken.
        val geoDominant = !disableGeoDominance &&
                leaderProtectable &&
                leaderRrf >= GEO_DOMINANCE_RRF_MIN &&
                (rrfGap >= GEO_DOMINANCE_RRF_GAP ||
                        (leaderVotes >= GEO_DOMINANCE_MIN_VOTES &&
                                rrfGap >= GEO_DOMINANCE_VOTES_MIN_GAP))

        // Geo-dominance protects the leader's *anchor word*, not its exact
        // expansion string. The leader's expansion/truncation variants are
        // injected as suggestions copied from pred (same word_index, same
        // bbox), so among candidates sharing the leader's word_index let the
        // text-shape rerank pick the best-scoring variant — that's how the
        // correct truncation "0011 of 0012" beats the over-expanded pred
        // "0011 of 0012 Tie # 1" while still refusing to jump to a different
        // OCR word. Falls back to pred (which always shares its own index).
        val winner = if (geoDominant) {
            combined.firstOrNull {
                (it.candidate["word_index"] as? Number)?.toInt() == leaderWi
            }?.candidate ?: pred
        } else {
            reranked[0]
        }
        debugLog?.add(
            "  geoDominant=$geoDominant (leaderRrf=${"%.3f".format(leaderRrf)}, "
                    + "runnerUp=${"%.3f".format(runnerUpRrf)}, votes=$leaderVotes vs $runnerUpVotes) "
                    + "winner: \"${winner["text"]}\""
        )
        predictions[field] = winner
        winner["geo_rank"] = 0
        val newSuggs = reranked.filter { it !== winner }.mapIndexed { rank, s ->
            s["geo_rank"] = rank + 1
            s
        }
        suggestionsMap[field] = newSuggs
    }
}

// --- Text-score-based dedup (post-rerank) ---

/**
 * Resolve word collisions across fields using the text-shape score.
 *
 * Runs in two passes after text-shape rerank stamps `text_dist` on each
 * candidate:
 *   1. word_index collisions — two fields' top-1 predictions are the same
 *      OCR word.
 *   2. text collisions — two fields' top-1 predictions have different
 *      word_indices but expand to the same text (e.g. `08` and `04` both
 *      expand to `08/04/2026`).
 *
 * In both passes the field with the smaller `text_dist` keeps the value;
 * losers fall back to their next-best rerank suggestion. Each pass iterates
 * because resolving one collision can create another. Bounded to 10 passes.
 */
internal fun dedupByTextScore(
    predictions: MutableMap<String, MutableMap<String, Any?>>,
    suggestionsMap: MutableMap<String, List<MutableMap<String, Any?>>>,
) {
    if (predictions.isEmpty()) return

    fun textDist(pred: Map<String, Any?>): Double =
        (pred["text_dist"] as? Number)?.toDouble() ?: Double.MAX_VALUE

    fun rrfScore(pred: Map<String, Any?>): Double =
        (pred["rrf_score"] as? Number)?.toDouble() ?: 0.0

    fun bbox(pred: Map<String, Any?>): DoubleArray? {
        val raw = pred["bbox"]
        val list = (raw as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: return null
        return if (list.size == 4) list.toDoubleArray() else null
    }

    /** Two predictions count as the *same line* when their bbox y-ranges
     *  overlap by at least half the shorter bbox's height. */
    fun sharesLine(a: DoubleArray, b: DoubleArray): Boolean {
        val overlap = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
        if (overlap <= 0.0) return false
        val shorter = min(a[3] - a[1], b[3] - b[1])
        return shorter > 0 && overlap >= shorter * 0.5
    }

    fun demoteLosers(losingFields: List<String>) {
        for (losingField in losingFields) {
            val suggs = suggestionsMap[losingField] ?: continue
            val newPred = suggs.firstOrNull() ?: continue
            val oldPred = predictions[losingField] ?: mutableMapOf()
            predictions[losingField] = newPred
            suggestionsMap[losingField] = suggs.drop(1) + oldPred
        }
    }

    // Pass 1: word_index collisions
    data class WordClaim(val field: String, val textDist: Double, val rrf: Double)
    for (i in 0 until 10) {
        val claims = HashMap<Int, MutableList<WordClaim>>()
        for ((field, pred) in predictions) {
            val wi = (pred["word_index"] as? Number)?.toInt() ?: continue
            claims.getOrPut(wi) { mutableListOf() }.add(WordClaim(field, textDist(pred), rrfScore(pred)))
        }
        var anyCollision = false
        for ((_, claimants) in claims) {
            if (claimants.size <= 1) continue
            anyCollision = true
            // Smallest text_dist wins (best text-shape match keeps the word).
            // Exact text ties — the norm for sibling fields whose template
            // values share one shape (e.g. three MAC addresses) — fall to
            // geometric strength (higher fused RRF score wins), so the field
            // whose own key sits closest keeps the word instead of whichever
            // sorts first alphabetically. Field name is the final determinism
            // tiebreak.
            val sorted = claimants.sortedWith(
                compareBy({ it.textDist }, { -it.rrf }, { it.field }),
            )
            demoteLosers(sorted.drop(1).map { it.field })
        }
        if (!anyCollision) break
    }

    // Pass 2: text collisions (same-line only).
    for (i in 0 until 10) {
        data class TextClaim(val field: String, val textDist: Double, val bbox: DoubleArray)
        val textClaims = HashMap<String, MutableList<TextClaim>>()
        for ((field, pred) in predictions) {
            val text = pred["text"]?.toString() ?: continue
            if (text.isEmpty()) continue
            val bb = bbox(pred) ?: continue
            textClaims.getOrPut(text) { mutableListOf() }.add(TextClaim(field, textDist(pred), bb))
        }
        var anyCollision = false
        for ((_, claimants) in textClaims) {
            if (claimants.size <= 1) continue
            val sorted = claimants.sortedWith(compareBy({ it.textDist }, { it.field }))
            val winner = sorted[0]
            val losersOnSameLine = mutableListOf<String>()
            for (c in sorted.drop(1)) {
                if (sharesLine(winner.bbox, c.bbox)) losersOnSameLine.add(c.field)
            }
            if (losersOnSameLine.isNotEmpty()) {
                anyCollision = true
                demoteLosers(losersOnSameLine)
            }
        }
        if (!anyCollision) break
    }
}

// --- Final confidence finalization ---
//
// Run after [rerankWithTextShape] + [dedupByTextScore]. Reads each candidate's
// pre-stamped `geo_sum` (from Matching.fuse) and `text_dist` (from rerank),
// derives text_rank within the per-field candidate pool, and writes the
// blended `confidence = 0.7·geo_sum + 0.3·text_score` back onto the dict.
//
// Barcode-association bonus and the 1.0 cap are applied later in
// PredictionEngine, after the per-prediction association check has run.
internal fun finalizeConfidence(
    predictions: MutableMap<String, MutableMap<String, Any?>>,
    suggestionsMap: MutableMap<String, List<MutableMap<String, Any?>>>,
    maxGeoSum: Double = 1.0,
) {
    // `maxGeoSum` is the maximum geoSum a perfect-rank-0 prediction could
    // achieve given which matchers the template actually has (sum of each
    // active matcher's rank-0 weight). A KV-only template tops out at 0.45;
    // dividing by 0.45 keeps a perfect prediction reading as 1.0 rather than
    // 0.45. All-four-matcher templates have maxGeoSum = 1.0 → no change.
    val geoSumDivisor = if (maxGeoSum > 0) maxGeoSum else 1.0

    fun geoSum(d: Map<String, Any?>): Double =
        (d["geo_sum"] as? Number)?.toDouble() ?: 0.0

    fun textDist(d: Map<String, Any?>): Double =
        (d["text_dist"] as? Number)?.toDouble() ?: Double.MAX_VALUE

    for (field in predictions.keys.toList()) {
        val pred = predictions[field] ?: continue
        val suggs = suggestionsMap[field] ?: emptyList()

        // Build a (idx, text_dist) pool to derive text ranks. If text_dist is
        // missing (rerank skipped the field), all candidates fall through with
        // the sentinel and get text_rank 0 — equivalent to "no text comparison
        // ran, give the geo-only score a full text-component contribution
        // rather than penalising the absence."
        data class PoolItem(val idx: Int, val td: Double)
        val pool = mutableListOf(PoolItem(0, textDist(pred)))
        for ((i, s) in suggs.withIndex()) pool.add(PoolItem(i + 1, textDist(s)))

        val allMissing = pool.all { it.td == Double.MAX_VALUE }
        val ranks: Map<Int, Int> = if (allMissing) {
            pool.associate { it.idx to 0 }
        } else {
            val sorted = pool.sortedBy { it.td }
            sorted.withIndex().associate { (rank, item) -> item.idx to rank }
        }

        fun finalize(d: MutableMap<String, Any?>, idx: Int) {
            val textScore = rankWeight(TEXT_RANK_WEIGHTS, ranks[idx] ?: 0)
            // Normalise the geo component by the template's maximum possible
            // geoSum so a perfect prediction reads as 1.0 regardless of how
            // many matchers the template has. Clamped post-blend to keep
            // confidences in [0, 1]; the barcode-association bonus added later
            // in PredictionEngine is re-clamped there.
            val normalizedGeoSum = min(1.0, geoSum(d) / geoSumDivisor)
            val conf = min(1.0, GEO_BLEND_WEIGHT * normalizedGeoSum + TEXT_BLEND_WEIGHT * textScore)
            d["confidence"] = conf
        }

        finalize(pred, 0)
        for ((i, s) in suggs.withIndex()) finalize(s, i + 1)
    }
}

// --- Text expansion ---

internal enum class ExpansionKind { SINGLE_WORD, MULTI_WORD }

/**
 * A single expansion result tied to a specific OCR occurrence of the predicted
 * word. When the same predicted word appears at multiple positions in OCR
 * (e.g. "SKHYNIX" at two places, only one followed by "-SLS"), each occurrence
 * produces its own expansion plus its own bbox / word_index. Emitting all of
 * them as separate reranker candidates lets the text-shape stage pick the
 * *position* whose surrounding OCR best matches the template.
 */
internal data class ExpansionCandidate(
    val text: String,
    val bbox: DoubleArray,
    /** Union bbox of ALL OCR tokens the expansion covers (== [bbox] when it's a
     *  single token). Used for the emitted field geometry so an expanded value
     *  reports the box spanning its tokens, not just the starting one. */
    val spanBbox: DoubleArray,
    /** Index into the *filtered* word list (-1 if absent). */
    val wordIndex: Int,
    val kind: ExpansionKind,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExpansionCandidate) return false
        return text == other.text && bbox.contentEquals(other.bbox) &&
                spanBbox.contentEquals(other.spanBbox) &&
                wordIndex == other.wordIndex && kind == other.kind
    }

    override fun hashCode(): Int {
        var h = text.hashCode()
        h = 31 * h + bbox.contentHashCode()
        h = 31 * h + spanBbox.contentHashCode()
        h = 31 * h + wordIndex
        h = 31 * h + kind.hashCode()
        return h
    }
}

/**
 * Return both the whitespace-bounded (single-word) and newline-bounded
 * (multi-word) expansions of the predicted word from the raw OCR text, plus
 * any distinct per-occurrence candidates. Caller decides which to use as
 * primary and whether to add the others to the rerank pool.
 */
internal data class TextExpansions(
    val singleWord: String,
    /** Union bbox of the tokens [singleWord] covers (null if it didn't expand
     *  past the predicted word). The emitted field bbox/corners use this so the
     *  geometry matches the expanded text, not the initial predicted word. */
    val singleWordBbox: DoubleArray? = null,
    val multiWord: String,
    /** Union bbox of the tokens [multiWord] covers (null if it didn't expand). */
    val multiWordBbox: DoubleArray? = null,
    /** Distinct per-occurrence candidates that differ in text from [singleWord]
     *  / [multiWord]. Each carries the bbox + filtered word_index of the OCR
     *  occurrence it came from. Empty when the predicted word has one OCR
     *  occurrence or every occurrence expands to the same text. */
    val alternateOccurrences: List<ExpansionCandidate> = emptyList(),
)

/**
 * Peel the printed key off an expanded value.
 *
 * [valueSpan] is the `[start, end)` character range the PREDICTED word occupies
 * within [text] (null when unknown). When a key variant occurs two or more
 * times in [text] — repeated key/value groups on one line, e.g.
 * `"MAC: aa:bb MAC: cc:dd"` — the strip runs POSITIONALLY against that span
 * (mirrors Python `_expand_text`): keep only the segment between the nearest key
 * occurrence ending before the value and the first one starting after it. Lines
 * with 0-1 occurrences of the variant fall through to the legacy START/END
 * anchored strip below, byte-identical to the previous behaviour.
 */
private fun stripLeadingKey(
    text: String,
    keyText: String,
    matchedKeyText: String = "",
    valueSpan: Pair<Int, Int>? = null,
): String {
    if (keyText.isEmpty() && matchedKeyText.isEmpty()) return text
    val keyVariants = mutableListOf<String>()
    if (keyText.isNotEmpty()) {
        keyVariants.add(keyText)
        keyVariants.addAll(keyText.split(" ").sortedByDescending { it.length })
    }
    // OCR can misread the key glyphs (template "Qty" vs scan "Oty"); the
    // literal template key won't strip that prefix, so also try the actual
    // matched OCR token. Appended after the template variants so an exact
    // template-key strip still wins when both would match.
    if (matchedKeyText.isNotEmpty() && matchedKeyText !in keyVariants) {
        keyVariants.add(matchedKeyText)
        if (' ' in matchedKeyText) {
            keyVariants.addAll(matchedKeyText.split(" ").sortedByDescending { it.length })
        }
    }
    val leadingStripRe = Regex("^[\\s:;.#\\-]*")
    val trailingStripRe = Regex("[\\s:;.#\\-]*$")
    for (kv in keyVariants) {
        val escaped = Regex.escape(kv)

        // POSITIONAL strip (mirrors Python `_expand_text`): the key is printed
        // more than once on this line, so anchoring on the FIRST occurrence
        // would hand this field the whole rest of the line (its own value plus
        // every sibling's). Cut through the nearest key occurrence ending
        // before the predicted word and truncate before the first one starting
        // after it, so the value keeps only its own segment.
        if (valueSpan != null) {
            val (vs, ve) = valueSpan
            val boundaryRe = Regex(
                "(?<![a-zA-Z0-9])$escaped(?![a-zA-Z0-9])",
                RegexOption.IGNORE_CASE,
            )
            val bMatches = boundaryRe.findAll(text).toList()
            if (bMatches.size >= 2) {
                val before = bMatches.filter { it.range.last + 1 <= vs }
                val afterMs = bMatches.filter { it.range.first >= ve }
                val segStart = before.lastOrNull()?.let { it.range.last + 1 } ?: 0
                val segEnd = afterMs.firstOrNull()?.range?.first ?: text.length
                if (segEnd > segStart && segEnd <= text.length) {
                    val segment = text.substring(segStart, segEnd)
                        .replace(leadingStripRe, "")
                        .trimEnd()
                    if (segment.isNotEmpty()) return segment
                }
            }
        }

        // START anchor: variant sits at the very beginning of text (optional
        // leading whitespace) and is followed by a non-alnum boundary. The
        // `(?![a-zA-Z0-9])` keeps a single-digit variant like "1" (from key
        // "SN 1") from matching the leading "1" of value "12345".
        val startRe = Regex("^\\s*$escaped(?![a-zA-Z0-9])", RegexOption.IGNORE_CASE)
        startRe.find(text)?.let { m ->
            val after = text.substring(m.range.last + 1)
            val stripped = after.replace(leadingStripRe, "")
            if (stripped.isNotEmpty()) return stripped
        }

        // END anchor: variant sits at the very end of text (optional trailing
        // whitespace) and is preceded by a non-alnum boundary. Mirror of START
        // for layouts where the OCR captures "<value> <key>".
        val endRe = Regex("(?<![a-zA-Z0-9])$escaped\\s*$", RegexOption.IGNORE_CASE)
        endRe.find(text)?.let { m ->
            val before = text.substring(0, m.range.first)
            val stripped = before.replace(trailingStripRe, "")
            if (stripped.isNotEmpty()) return stripped
        }
    }
    return text
}

/**
 * Geometry-based line reconstruction used as a fallback when the regex /
 * raw-text expansion in [expandText] can't join a value to its neighbours —
 * e.g. a single-character value ("3") whose unit was OCR'd inconsistently
 * between the structured word boxes ("lb") and the raw text stream ("Ib"),
 * which fails the context guard; or any backend that doesn't populate
 * [WordBox.trailingSeparator] (ML Kit), which disables the
 * separator walk.
 *
 * Walks outward from [startIdx] over the OCR word list, collecting the
 * contiguous run of tokens sharing [startIdx]'s text line (vertical centre
 * within 0.6x its height) whose horizontal gap to the neighbour stays within
 * [maxGapRatio]x the start token's height. Returns the run joined by single
 * spaces, plus the start token's character span within it. Conservative by
 * design: the gap bound stops it crossing into a separate column, so it never
 * reaches further than the raw-text whole-line expansion would.
 */
private fun stitchSameLineNeighbors(
    startIdx: Int,
    tokens: List<WordBox>,
    maxGapRatio: Double = 1.5,
): TokenWalk {
    val startText = tokens.getOrNull(startIdx)?.text ?: ""
    fun bare() = TokenWalk(startText, 0, startText.length)
    if (startIdx !in tokens.indices) return TokenWalk("", 0, 0)
    val base = tokens[startIdx].bbox
    val baseH = base[3] - base[1]
    if (baseH <= 0) return bare()
    val baseMidY = (base[1] + base[3]) / 2.0
    val line = tokens.indices
        .filter { abs((tokens[it].bbox[1] + tokens[it].bbox[3]) / 2.0 - baseMidY) <= baseH * 0.6 }
        .sortedBy { tokens[it].bbox[0] }
    val pos = line.indexOf(startIdx)
    if (pos < 0) return bare()
    val maxGap = baseH * maxGapRatio
    var lo = pos
    while (lo > 0 && tokens[line[lo]].bbox[0] - tokens[line[lo - 1]].bbox[2] <= maxGap) lo--
    var hi = pos
    while (hi + 1 < line.size && tokens[line[hi + 1]].bbox[0] - tokens[line[hi]].bbox[2] <= maxGap) hi++
    val text = (lo..hi).joinToString(" ") { tokens[line[it]].text }
    // Span of the start token within the joined run (single-space joins), so a
    // positional key-strip works on this path too.
    var prefixLen = 0
    for (k in lo until pos) prefixLen += tokens[line[k]].text.length + 1
    return TokenWalk(text, prefixLen, prefixLen + startText.length)
}

/** Union bbox of the tokens in the run [walkAdjacentTokens] joins from [startIdx]
 *  **whose text is part of [text]** (case-insensitive). Filtering by the final
 *  (key-stripped) value drops the key and any other-line tokens the walk crossed,
 *  so the box matches the emitted value instead of the whole line/observation.
 *  Returns null when no token matches. */
internal fun spanMatchingText(
    startIdx: Int,
    tokens: List<WordBox>,
    stopOnWhitespaceSeparator: Boolean,
    text: String,
): DoubleArray? {
    if (startIdx !in tokens.indices) return null
    var lo = startIdx
    var hi = startIdx
    var i = startIdx
    while (i + 1 < tokens.size) {
        val sep = tokens[i].trailingSeparator ?: break
        if (stopOnWhitespaceSeparator && sep.any { it.isWhitespace() }) break
        hi = i + 1; i++
    }
    var j = startIdx
    while (j > 0) {
        val sep = tokens[j - 1].trailingSeparator ?: break
        if (stopOnWhitespaceSeparator && sep.any { it.isWhitespace() }) break
        lo = j - 1; j--
    }
    val hay = text.lowercase()
    val boxes = (lo..hi).mapNotNull { k ->
        val t = tokens[k].text.lowercase()
        if (t.isNotEmpty() && hay.contains(t) && tokens[k].bbox.size == 4) tokens[k].bbox else null
    }
    return if (boxes.isEmpty()) null else unionBboxes(boxes)
}

/** Axis-aligned union of the given bboxes ([minX,minY,maxX,maxY]). */
private fun unionBboxes(boxes: List<DoubleArray>): DoubleArray {
    val valid = boxes.filter { it.size == 4 }
    if (valid.isEmpty()) return doubleArrayOf()
    var x0 = valid[0][0]; var y0 = valid[0][1]; var x1 = valid[0][2]; var y1 = valid[0][3]
    for (b in valid) {
        x0 = min(x0, b[0]); y0 = min(y0, b[1]); x1 = max(x1, b[2]); y1 = max(y1, b[3])
    }
    return doubleArrayOf(x0, y0, x1, y1)
}

internal fun expandText(
    predictedWord: String,
    wordIndex: Int,
    @Suppress("UNUSED_PARAMETER") templateValue: String,
    rawOcrText: String,
    wordBoxTuplesFiltered: List<WordBox>,
    wordBoxTuplesUnfiltered: List<WordBox>,
    keyText: String = "",
    matchedKeyText: String = "",
): TextExpansions {
    if (rawOcrText.isEmpty() || predictedWord.isEmpty()) {
        return TextExpansions(singleWord = predictedWord, multiWord = predictedWord)
    }
    if (wordIndex >= wordBoxTuplesFiltered.size) {
        return TextExpansions(singleWord = predictedWord, multiWord = predictedWord)
    }
    val predBbox = wordBoxTuplesFiltered[wordIndex].bbox
    val predH = predBbox[3] - predBbox[1]
    if (predH <= 0) return TextExpansions(singleWord = predictedWord, multiWord = predictedWord)

    val predWordLower = predictedWord.lowercase()
    val ocrPositions = mutableListOf<Pair<Int, DoubleArray>>()
    for ((i, wb) in wordBoxTuplesUnfiltered.withIndex()) {
        if (wb.text.lowercase() == predWordLower) ocrPositions.add(i to wb.bbox)
    }
    if (ocrPositions.isEmpty()) return TextExpansions(singleWord = predictedWord, multiWord = predictedWord)

    // `span` is the predicted word's character range within `text` — the key
    // strip uses it to strip relative to the value's own key occurrence when
    // the same key is printed more than once on the line.
    data class Candidate(
        val text: String,
        val distance: Double,
        val occIdx: Int,
        val span: Pair<Int, Int>,
    )
    // Per-occurrence record carrying both expansions plus the OCR bbox /
    // unfiltered index — drives the alternate-occurrence injection below.
    data class OccRecord(
        val swText: String,
        val mwText: String,
        val bbox: DoubleArray,
        val unfilteredIdx: Int,
        /** Predicted word's character spans within [swText] / [mwText]. */
        val swSpan: Pair<Int, Int>,
        val mwSpan: Pair<Int, Int>,
    )
    val swCandidates = mutableListOf<Candidate>()
    val mwCandidates = mutableListOf<Candidate>()
    val perOccurrence = mutableListOf<OccRecord>()

    val pattern = Regex(
        "(?<![a-zA-Z0-9])" + Regex.escape(predictedWord) + "(?![a-zA-Z0-9])",
        RegexOption.IGNORE_CASE,
    )
    val matches = pattern.findAll(rawOcrText).toList()

    // When the predicted word appears multiple times in rawOcrText, the
    // positional pairing `ocrPositions[i] ↔ matches[i]` can drift apart (the
    // unfiltered word list has been pruned by orientation/rotation filters
    // while rawOcrText hasn't), mispairing the matcher's chosen occurrence.
    // The adjacency walk bypasses rawOcrText entirely by following each
    // token's `trailingSeparator`. It's only usable when the OCR backend
    // populated separators — gate on availability so platforms that don't
    // (ML Kit) fall back to the regex path with no regression.
    val useAdjacencyWalk = matches.size > 1 &&
            wordBoxTuplesUnfiltered.any { it.trailingSeparator != null }

    for ((i, occ) in ocrPositions.withIndex()) {
        val (occIdx, occBbox) = occ
        val overlaps = occBbox[0] <= predBbox[2] && occBbox[2] >= predBbox[0] &&
                occBbox[1] <= predBbox[3] && occBbox[3] >= predBbox[1]
        val dist = if (overlaps) 0.0
        else min(abs(occBbox[0] - predBbox[0]), abs(occBbox[2] - predBbox[2])) +
                abs(occBbox[1] - predBbox[1])

        var swText = predictedWord
        var mwText = predictedWord
        // Position of the predicted word WITHIN each expanded candidate — the
        // key strip uses it to strip relative to the value's own key occurrence
        // when the same key is printed more than once on the line.
        var swSpan = 0 to predictedWord.length
        var mwSpan = 0 to predictedWord.length

        if (useAdjacencyWalk) {
            val swWalk = walkAdjacentTokensWithSpan(occIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = true)
            swText = swWalk.text
            swSpan = swWalk.spanStart to swWalk.spanEnd
            val mwWalk = walkAdjacentTokensWithSpan(occIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = false)
            mwText = mwWalk.text
            mwSpan = mwWalk.spanStart to mwWalk.spanEnd
        } else {
            val prevWord = if (occIdx > 0) wordBoxTuplesUnfiltered[occIdx - 1].text else null
            val nextWord = if (occIdx + 1 < wordBoxTuplesUnfiltered.size) wordBoxTuplesUnfiltered[occIdx + 1].text else null

            val match = matches.getOrNull(i)
            if (match != null) {
                val mStart = match.range.first
                val mEnd = match.range.last + 1

                val contextBefore = rawOcrText.substring(max(0, mStart - 50), mStart)
                val contextAfter = rawOcrText.substring(mEnd, min(rawOcrText.length, mEnd + 50))

                var contextOk = true
                if (prevWord != null && prevWord.lowercase() !in contextBefore.lowercase()) contextOk = false
                if (nextWord != null && nextWord.lowercase() !in contextAfter.lowercase()) contextOk = false

                if (contextOk || (prevWord == null && nextWord == null)) {
                    // Single-word expansion (whitespace boundaries)
                    var swStart = mStart
                    var swEnd = mEnd
                    while (swStart > 0 && !rawOcrText[swStart - 1].isWhitespace()) swStart--
                    while (swEnd < rawOcrText.length && !rawOcrText[swEnd].isWhitespace()) swEnd++
                    val rawSw = rawOcrText.substring(swStart, swEnd)
                    // Leading whitespace removed by the trim shifts the span left.
                    val swLeadWs = rawSw.length - rawSw.trimStart().length
                    swText = rawSw.trim()
                    swSpan = (mStart - swStart - swLeadWs) to (mEnd - swStart - swLeadWs)

                    // Multi-word expansion (newline boundaries)
                    var mwStart = mStart
                    var mwEnd = mEnd
                    while (mwStart > 0 && rawOcrText[mwStart - 1] != '\n') mwStart--
                    while (mwEnd < rawOcrText.length && rawOcrText[mwEnd] != '\n') mwEnd++
                    val rawMw = rawOcrText.substring(mwStart, mwEnd)
                    val mwLeadWs = rawMw.length - rawMw.trimStart().length
                    mwText = rawMw.trim()
                    mwSpan = (mStart - mwStart - mwLeadWs) to (mEnd - mwStart - mwLeadWs)
                }
            }
        }

        // Geometric fallback: when neither the separator walk nor the regex /
        // raw-text path expanded this occurrence (mwText is still the bare
        // predicted word), reconstruct the line directly from same-line OCR
        // bboxes. Robust to raw-text/word-box OCR disagreements (e.g. a unit
        // "lb" read as "Ib" in the raw stream defeats the context guard above)
        // and works on backends without trailingSeparator. The single-word form
        // stays the bare token; only the multi-word (line) form is recovered.
        if (mwText == predictedWord) {
            val stitched = stitchSameLineNeighbors(occIdx, wordBoxTuplesUnfiltered)
            if (stitched.text.length > mwText.length) {
                mwText = stitched.text
                mwSpan = stitched.spanStart to stitched.spanEnd
            }
        }

        swCandidates.add(Candidate(swText, dist, occIdx, swSpan))
        mwCandidates.add(Candidate(mwText, dist, occIdx, mwSpan))
        perOccurrence.add(OccRecord(swText, mwText, occBbox, occIdx, swSpan, mwSpan))
    }

    fun pickBest(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val bestDist = candidates.minOf { it.distance }
        val ties = candidates.filter { it.distance == bestDist }
        return ties.maxByOrNull { it.text.length }
    }

    val swBestCand = pickBest(swCandidates)
    val mwBestCand = pickBest(mwCandidates)
    val swBest = stripLeadingKey(swBestCand?.text ?: predictedWord, keyText, matchedKeyText, swBestCand?.span)
    val mwBest = stripLeadingKey(mwBestCand?.text ?: predictedWord, keyText, matchedKeyText, mwBestCand?.span)
    // Span bbox for the chosen expansion — the union of the walked tokens whose
    // text is part of the FINAL (key-stripped) value, so the box matches the
    // emitted text and excludes the key / rest of the line. Null when it didn't
    // grow past the word (caller keeps the original per-word bbox).
    val swBestBbox = if (swBestCand != null && swBest != predictedWord)
        spanMatchingText(swBestCand.occIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = true, text = swBest) else null
    val mwBestBbox = if (mwBestCand != null && mwBest != predictedWord)
        spanMatchingText(mwBestCand.occIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = false, text = mwBest) else null

    // Per-occurrence alternate candidates: for every OCR occurrence whose
    // expansion produces a distinct text from swBest / mwBest, emit a separate
    // candidate carrying that occurrence's bbox + filtered word_index. Lets the
    // reranker treat each position as an independent candidate and pick the one
    // whose text-shape best matches the template (the duplicate-word path).
    val seenTexts = hashSetOf(swBest, mwBest, predictedWord)
    val alternates = mutableListOf<ExpansionCandidate>()
    for (occ in perOccurrence) {
        val sw = stripLeadingKey(occ.swText, keyText, matchedKeyText, occ.swSpan)
        val mw = stripLeadingKey(occ.mwText, keyText, matchedKeyText, occ.mwSpan)
        val filteredIdx = findFilteredIndex(occ.bbox, wordBoxTuplesFiltered)
        if (sw.isNotEmpty() && sw !in seenTexts) {
            seenTexts.add(sw)
            val span = spanMatchingText(occ.unfilteredIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = true, text = sw) ?: occ.bbox
            alternates.add(ExpansionCandidate(sw, occ.bbox, span, filteredIdx, ExpansionKind.SINGLE_WORD))
        }
        if (mw.isNotEmpty() && mw !in seenTexts) {
            seenTexts.add(mw)
            val span = spanMatchingText(occ.unfilteredIdx, wordBoxTuplesUnfiltered, stopOnWhitespaceSeparator = false, text = mw) ?: occ.bbox
            alternates.add(ExpansionCandidate(mw, occ.bbox, span, filteredIdx, ExpansionKind.MULTI_WORD))
        }
    }
    return TextExpansions(
        singleWord = swBest,
        singleWordBbox = swBestBbox,
        multiWord = mwBest,
        multiWordBbox = mwBestBbox,
        alternateOccurrences = alternates,
    )
}

/**
 * Reconstruct the joined-token form around [startIdx] by walking the OCR word
 * list outward via each token's [WordBox.trailingSeparator]. Used by the
 * multi-occurrence path in [expandText] to bypass fragile regex/rawOcrText
 * positional pairing.
 *
 * Forward walk appends `tokens[i].trailingSeparator + tokens[i+1].text` while
 * the current token's separator is non-null. Backward walk prepends
 * `tokens[i-1].text + tokens[i-1].trailingSeparator` (the previous token is in
 * the same observation iff its separator is non-null).
 *
 * [stopOnWhitespaceSeparator] = true mirrors the whitespace-bounded
 * single-word expansion; false mirrors the newline-bounded multi-word one
 * (stops only at observation boundaries).
 */
internal fun walkAdjacentTokens(
    startIdx: Int,
    tokens: List<WordBox>,
    stopOnWhitespaceSeparator: Boolean,
): String = walkAdjacentTokensWithSpan(startIdx, tokens, stopOnWhitespaceSeparator).text

/**
 * Same walk as [walkAdjacentTokens], additionally returning the character span
 * of the START token within the joined result. [expandText] carries the span
 * into [stripLeadingKey], which uses it to strip positionally when the key text
 * is printed more than once on the joined line.
 */
internal fun walkAdjacentTokensWithSpan(
    startIdx: Int,
    tokens: List<WordBox>,
    stopOnWhitespaceSeparator: Boolean,
): TokenWalk {
    if (startIdx < 0 || startIdx >= tokens.size) return TokenWalk("", 0, 0)
    var result = tokens[startIdx].text

    var i = startIdx
    while (i + 1 < tokens.size) {
        val sep = tokens[i].trailingSeparator ?: break
        if (stopOnWhitespaceSeparator && sep.any { it.isWhitespace() }) break
        result += sep + tokens[i + 1].text
        i++
    }

    var j = startIdx
    var prefixLen = 0
    while (j > 0) {
        val sep = tokens[j - 1].trailingSeparator ?: break
        if (stopOnWhitespaceSeparator && sep.any { it.isWhitespace() }) break
        result = tokens[j - 1].text + sep + result
        prefixLen += tokens[j - 1].text.length + sep.length
        j--
    }

    return TokenWalk(result, prefixLen, prefixLen + tokens[startIdx].text.length)
}

/** Joined text of a token walk plus the `[spanStart, spanEnd)` character range
 *  the START token occupies within it. */
internal data class TokenWalk(val text: String, val spanStart: Int, val spanEnd: Int)

/**
 * Look up the index of [targetBbox] in [filtered] by approximate-coord match.
 * [filterWordBoxes] substitutes 1.0 for any 0.0 coord, so an exact equality
 * check would miss some cases — a tolerance of 2.0 pixels covers the
 * substitution plus rounding through scaling. Returns -1 if not found.
 */
private fun findFilteredIndex(targetBbox: DoubleArray, filtered: List<WordBox>): Int {
    if (targetBbox.size < 4) return -1
    for ((i, wb) in filtered.withIndex()) {
        val b = wb.bbox
        if (b.size < 4) continue
        if (abs(b[0] - targetBbox[0]) <= 2.0 && abs(b[1] - targetBbox[1]) <= 2.0 &&
            abs(b[2] - targetBbox[2]) <= 2.0 && abs(b[3] - targetBbox[3]) <= 2.0
        ) {
            return i
        }
    }
    return -1
}
