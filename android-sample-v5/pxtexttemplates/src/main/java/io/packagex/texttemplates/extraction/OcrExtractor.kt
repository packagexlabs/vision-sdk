package io.packagex.texttemplates.extraction

import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.extraction.models.OcrBlock
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.extraction.models.OcrLine
import io.packagex.texttemplates.extraction.models.OcrWord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

internal class OcrExtractor constructor() {

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(image: InputImage): OcrFrameResult {
        val result = recognizer.process(image).await()

        // ML Kit coordinate-space quirk: when the InputImage carries a
        // rotation, `boundingBox` is returned rotation-compensated (upright
        // space) but `cornerPoints` are NOT — they stay in the raw sensor
        // frame (known ML Kit issue). The prediction engine requires every
        // coordinate of a frame to share one space; feeding it the mixed pair
        // makes the gross-orientation detector (corner-driven) fire a label
        // rotation that the already-upright bboxes don't need — text survives
        // only because deskew rebuilds bboxes from the rotated corners, while
        // barcode bounds (upright, no corners) get spuriously rotated 90°.
        // Normalise here: rotate corners sensor→upright so the whole frame is
        // upright space, and flag it `coordsPreRotated` (same convention as
        // iOS Vision).
        val rotation = image.rotationDegrees
        val sensorW = image.width
        val sensorH = image.height

        val blocks = result.textBlocks.map { block ->
            val lines = block.lines.map { line ->
                // Reconstruct each word's trailing separator (the chars between
                // this element and the next within line.text). ML Kit's
                // `byWords` segmentation discards `-`, `/`, etc.; recovering
                // them lets the multi-occurrence expansion adjacency walk run
                // exactly as on iOS. We locate each element's text in line.text
                // with a monotonically advancing cursor (handles repeated
                // words) and capture the gap to the next element's start.
                val lineText = line.text
                val elements = line.elements
                var cursor = 0
                val elemSpans = elements.map { element ->
                    val start = lineText.indexOf(element.text, cursor).let { if (it < 0) cursor else it }
                    val end = start + element.text.length
                    cursor = end
                    start to end
                }
                val words = elements.flatMapIndexed { idx, element ->
                    val trailingSep: String? = if (idx + 1 < elements.size) {
                        val end = elemSpans[idx].second
                        val nextStart = elemSpans[idx + 1].first
                        if (nextStart in end..lineText.length) lineText.substring(end, nextStart) else null
                    } else null
                    // `byWords` segmentation drops punctuation that isn't
                    // between two words, so trailing value-connector chars on
                    // the LAST element of a line (e.g. the "=" in a Cisco
                    // "Prod.#: 8808=") are otherwise lost — trailingSep above
                    // only spans the gap to a *following* element. Re-attach the
                    // contiguous run of connector punctuation right after this
                    // element so the value survives to prediction. No-op when ML
                    // Kit already included it in element.text. Mirrors iOS.
                    val elementText = if (idx + 1 == elements.size) {
                        val sb = StringBuilder(element.text)
                        var t = elemSpans[idx].second
                        while (t < lineText.length && lineText[t] in TRAILING_CONNECTOR_CHARS) {
                            sb.append(lineText[t]); t++
                        }
                        sb.toString()
                    } else {
                        element.text
                    }
                    val baseWord = OcrWord(
                        text = elementText,
                        boundingBox = element.boundingBox?.toBoundingBox(),
                        confidence = element.confidence ?: 0f,
                        cornerPoints = element.cornerPoints?.map {
                            rotatePointToUpright(it.x, it.y, rotation, sensorW, sensorH)
                        },
                        trailingSeparator = trailingSep,
                    )
                    // Split KEY:VALUE-glued tokens into separate sub-words so
                    // `findKeyWord` can match the key half independently. ML Kit
                    // sometimes returns "Qty:0012" as one OCR element because `:`
                    // isn't a `byWords` boundary — without splitting, the KV
                    // matcher never sees "Qty" as a standalone key and expansion
                    // can never isolate the bare value. No-op when the token has
                    // no kv separator.
                    splitOnKvSeparator(baseWord)
                }
                OcrLine(
                    text = line.text,
                    words = words,
                    boundingBox = line.boundingBox?.toBoundingBox()
                )
            }

            val blockConfidence = block.lines
                .flatMap { line -> line.elements }
                .mapNotNull { element -> element.confidence }
                .average()
                .toFloat()
                .takeIf { value -> !value.isNaN() } ?: 0f

            OcrBlock(
                text = block.text,
                lines = lines,
                boundingBox = block.boundingBox?.toBoundingBox(),
                confidence = blockConfidence
            )
        }

        val averageConfidence = blocks
            .map { block -> block.confidence }
            .average()
            .toFloat()
            .takeIf { value -> !value.isNaN() } ?: 0f

        return OcrFrameResult(
            blocks = blocks,
            fullText = result.text,
            averageConfidence = averageConfidence,
            // Everything above is upright space: ML Kit bboxes natively, corner
            // points via rotatePointToUpright. Callers must pass upright frame
            // dims and rotation 0 to the prediction engine.
            coordsPreRotated = true,
        )
    }

    private fun android.graphics.Rect.toBoundingBox(): BoundingBox {
        return BoundingBox(left, top, width(), height())
    }

    /**
     * Rotate a sensor-space point CW by [rotationDegrees] into the upright
     * frame. Duplicates `Geometry.rotateCornerPoints` per-point — kept local
     * so the extraction layer stays free of prediction-package imports
     * (prediction already imports extraction.models; the reverse would be a
     * package cycle). Keep the two in lockstep: any convention change there
     * must be mirrored here, and vice versa.
     * [width]/[height] are the SENSOR buffer dims (pre-rotation).
     */
    private fun rotatePointToUpright(
        x: Int,
        y: Int,
        rotationDegrees: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> Pair(height - y, x)
        180 -> Pair(width - x, height - y)
        270 -> Pair(y, width - x)
        else -> Pair(x, y)
    }

    companion object {
        /** Characters that act as KEY-VALUE separators when OCR fails to split
         *  tokens at them. Mirrors `LEADING_KEY_RESIDUE_CHARS` in
         *  TextPostProcessor (that set = whitespace + exactly these chars) —
         *  keep the two in lockstep so a token split here produces sub-tokens
         *  whose key-residue `stripLeadingKeyResidue` can later recognise on
         *  the prediction side. NOT the same set as TextPostProcessor's
         *  `keyValueSeparators` (`#`/`:`), which is intentionally narrower —
         *  see the note there. */
        private val KV_SEPARATORS = setOf(':', ';', '|', ',')

        /** Value-connector punctuation re-attached to a line's final element
         *  when `byWords` segmentation strips it (e.g. the "=" in "8808="). A
         *  subset of TextPostProcessor's pattern connectors; "." and "," are
         *  excluded so trailing prose punctuation isn't glued onto values.
         *  Keep in lockstep with iOS `OcrExtractor.trailingConnectorChars`. */
        private val TRAILING_CONNECTOR_CHARS = setOf('=', '+', '-', '/', '_')

        /** "Soft" KV separators. Unlike the hard set above, these commonly occur
         *  INSIDE values — part numbers ("P25035-05-04"), SKUs ("FG-1801F"),
         *  dates ("2025-01-13"), decimals — so they only mark a key/value
         *  boundary when the prefix clearly reads as a key (see
         *  [softKvSeparatorIndex]). Added to recover OCR colon→dash/dot misreads
         *  such as "Shplo-AAFVO88" or "Qty.0027". */
        private val SOFT_KV_SEPARATORS = setOf('-', '.')

        /** Minimum alphabetic-prefix length for a soft separator to count as a
         *  boundary. 3 admits real keys like "Qty"/"PIN" while rejecting 2-char
         *  code prefixes like "FG-". */
        private const val SOFT_KV_MIN_KEY_LEN = 3

        /**
         * Index of the first soft separator (`-`/`.`) that plausibly divides a
         * KEY from a VALUE, or -1 when none qualifies. Guard: the prefix before
         * the separator must be purely alphabetic and at least
         * [SOFT_KV_MIN_KEY_LEN] chars. Only the FIRST soft separator is
         * considered — once a non-qualifying one is seen, splitting at a later
         * one would cut inside the value, so we bail.
         *
         * Admits : "Shplo-AAFVO88" → "Shplo"|"AAFVO88", "Qty.0027" → "Qty"|"0027"
         * Rejects: "P25035-05-04", "FG-1801F", "C1AJ83-06AA-0000",
         *          "2025-01-13", "-12.5" (numeric / short / non-key prefix)
         */
        private fun softKvSeparatorIndex(text: String): Int {
            for (i in text.indices) {
                if (text[i] !in SOFT_KV_SEPARATORS) continue
                val prefix = text.substring(0, i)
                return if (prefix.length >= SOFT_KV_MIN_KEY_LEN && prefix.all { it.isLetter() }) i else -1
            }
            return -1
        }

        /**
         * Split a single OCR word on its first KEY-VALUE separator, returning the
         * resulting sub-tokens with proportional bboxes. When the word contains
         * no such separator, returns `[word]` unchanged.
         *
         * ML Kit sometimes glues `"Key:Value"` into one OCR element because the
         * `:` (or `;`, `|`, `,`) isn't a `byWords` segmentation boundary. The
         * downstream KV matcher needs the key half to be its own OCR token so
         * `findKeyWord` can recognise it as a template key, and text expansion
         * needs the value half to be addressable on its own.
         *
         * Bbox slicing assumes horizontal text and proportional character
         * widths — the key gets `keyChars / totalChars` of the original width on
         * the left, the separator gets `1 / totalChars`, and the value gets the
         * remaining width on the right. Heights are preserved. The parent's
         * oriented corner quad is sliced at the same char fractions so each
         * sub-token keeps real corner points: without them, a later gross
         * rotation axis-swaps the sub-token's AABB (wide→tall) with no corners
         * to rebuild it, and `removeRotatedWords` discards it — dropping e.g.
         * the value half of "Order #:28058130" outright.
         *
         * `trailingSeparator` is preserved to match iOS:
         *   - the key sub-token's `trailingSeparator` is the literal separator
         *     character (so the adjacency walk in expansion can still
         *     reconstruct the joined form when needed)
         *   - the last sub-token inherits the parent's original
         *     `trailingSeparator` (the gap to the next element in the line)
         */
        private fun splitOnKvSeparator(word: OcrWord): List<OcrWord> {
            val bb = word.boundingBox ?: return listOf(word)
            val text = word.text
            // Hard separators (`: ; | ,`) split on the first occurrence; soft
            // separators (`- .`) only when the prefix reads as a key. Hard
            // takes precedence.
            val hardIdx = text.indexOfFirst { it in KV_SEPARATORS }
            val sepIdx = if (hardIdx >= 0) hardIdx else softKvSeparatorIndex(text)
            if (sepIdx < 0) return listOf(word)
            val keyPart = text.substring(0, sepIdx)
            val sepChar = text[sepIdx].toString()
            val valuePart = text.substring(sepIdx + 1)
            // Degenerate splits: empty key or empty value — keep the original
            // token. A leading ":foo" or trailing "foo:" isn't a key/value pair.
            if (keyPart.isEmpty() || valuePart.isEmpty()) return listOf(word)

            val totalChars = text.length
            val totalWidth = bb.width.toDouble()
            // Proportional widths in pixels — max(1, ...) so degenerate rounding
            // never produces a zero-width bbox that downstream code would reject.
            val keyWidthPx = maxOf(1, ((keyPart.length.toDouble() / totalChars) * totalWidth).roundToInt())
            val sepWidthPx = maxOf(1, ((1.0 / totalChars) * totalWidth).roundToInt())
            val valueWidthPx = maxOf(1, ((valuePart.length.toDouble() / totalChars) * totalWidth).roundToInt())

            val keyBox = BoundingBox(left = bb.left, top = bb.top, width = keyWidthPx, height = bb.height)
            val valueBox = BoundingBox(
                left = bb.left + keyWidthPx + sepWidthPx,
                top = bb.top,
                width = valueWidthPx,
                height = bb.height,
            )
            // Slice the parent's corner quad at the same char-proportional
            // offsets the bbox uses: key spans [0, keyChars/total]; value spans
            // [(keyChars+1)/total, 1] (the +1 accounts for the separator glyph).
            val keyCorners = sliceCorners(
                word.cornerPoints, 0.0, keyPart.length.toDouble() / totalChars,
            )
            val valueCorners = sliceCorners(
                word.cornerPoints, (keyPart.length + 1).toDouble() / totalChars, 1.0,
            )
            val keyOcrWord = OcrWord(
                text = keyPart,
                boundingBox = keyBox,
                confidence = word.confidence,
                cornerPoints = keyCorners,
                trailingSeparator = sepChar,
            )
            val valueOcrWord = OcrWord(
                text = valuePart,
                boundingBox = valueBox,
                confidence = word.confidence,
                cornerPoints = valueCorners,
                trailingSeparator = word.trailingSeparator,
            )
            // Value half might itself contain another kv separator (e.g.
            // "PartNo:SKU:001"). Recurse so all separators get split.
            return listOf(keyOcrWord) + splitOnKvSeparator(valueOcrWord)
        }

        /**
         * Interpolate a `[TL, TR, BR, BL]` corner quad to the sub-quad spanning
         * the text-reading fraction `[f0, f1]` (0 = start/left edge, 1 = end/
         * right edge). Returns null when the source isn't a usable 4-point quad,
         * so callers fall back to no corner points. Lets KV-split sub-tokens
         * inherit the parent's true orientation rather than an axis-aligned
         * guess, so they survive the gross-rotation + deskew pipeline.
         */
        private fun sliceCorners(
            corners: List<Pair<Int, Int>>?,
            f0: Double,
            f1: Double,
        ): List<Pair<Int, Int>>? {
            if (corners == null || corners.size != 4) return null
            val (tl, tr, br, bl) = corners
            fun lerp(a: Pair<Int, Int>, b: Pair<Int, Int>, f: Double) = Pair(
                (a.first + (b.first - a.first) * f).roundToInt(),
                (a.second + (b.second - a.second) * f).roundToInt(),
            )
            return listOf(
                lerp(tl, tr, f0), // TL
                lerp(tl, tr, f1), // TR
                lerp(bl, br, f1), // BR
                lerp(bl, br, f0), // BL
            )
        }
    }
}
