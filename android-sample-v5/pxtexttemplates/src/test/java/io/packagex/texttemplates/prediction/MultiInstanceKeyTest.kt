package io.packagex.texttemplates.prediction

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-instance KV key coverage — the Kotlin twin of the iOS
 * `TextTemplatesTests` multi-instance suite and the Python
 * `tests/test_multi_instance_keys.py`. Same fixtures, same expectations, so a
 * divergence between the three ports shows up as a failing test rather than a
 * field bound to the wrong printed key.
 */
class MultiInstanceKeyTest {

    private fun wb(text: String, vararg bbox: Double) = WordBox(text, bbox)

    /** Three "MAC:" key/value rows, one per line: key at x=10-50, value at
     *  x=60-160. Mirrors MAC_WORDS in the Python suite. */
    private val macWords = listOf(
        wb("MAC:", 10.0, 10.0, 50.0, 20.0), wb("AA:11", 60.0, 10.0, 160.0, 20.0),
        wb("MAC:", 10.0, 40.0, 50.0, 50.0), wb("BB:22", 60.0, 40.0, 160.0, 50.0),
        wb("MAC:", 10.0, 70.0, 50.0, 80.0), wb("CC:33", 60.0, 70.0, 160.0, 80.0),
        wb("SN:", 10.0, 100.0, 40.0, 110.0), wb("XYZ99", 60.0, 100.0, 140.0, 110.0),
    )

    // --- findAllKeyWords ---

    @Test
    fun findAllKeyWords_collectsEveryOccurrence() {
        val occs = findAllKeyWords("MAC:", macWords)
        assertEquals(3, occs.size)
        assertEquals(
            setOf(
                listOf(10.0, 10.0, 50.0, 20.0),
                listOf(10.0, 40.0, 50.0, 50.0),
                listOf(10.0, 70.0, 50.0, 80.0),
            ),
            occs.map { it.bbox.toList() }.toSet(),
        )
    }

    @Test
    fun findAllKeyWords_singleOccurrence() {
        assertEquals(1, findAllKeyWords("SN:", macWords).size)
    }

    @Test
    fun findAllKeyWords_absentKey() {
        assertTrue(findAllKeyWords("Nope", macWords).isEmpty())
    }

    @Test
    fun findAllKeyWords_multiwordKeyMergedBboxRemoval() {
        // The merged bbox equals no member word bbox — removal must be by
        // containment or the loop returns only the first hit.
        val words = listOf(
            wb("Serial", 10.0, 10.0, 40.0, 20.0), wb("Number", 45.0, 10.0, 90.0, 20.0),
            wb("Serial", 10.0, 40.0, 40.0, 50.0), wb("Number", 45.0, 40.0, 90.0, 50.0),
        )
        val occs = findAllKeyWords("Serial Number", words)
        assertEquals(2, occs.size)
        assertEquals("Serial Number", occs[0].text)
    }

    @Test
    fun findAllKeyWords_occurrenceCap() {
        val words = (0 until 20).map { i ->
            val y = 30.0 * i
            wb("Qty", 10.0, 10.0 + y, 40.0, 20.0 + y)
        }
        assertEquals(MAX_KEY_OCCURRENCES, findAllKeyWords("Qty", words).size)
    }

    // --- pickKeyWordByPosition ---

    @Test
    fun pickByPosition_picksNearestExpected() {
        val occs = findAllKeyWords("MAC:", macWords)
        val picked = pickKeyWordByPosition(occs, Pair(1.0, 4.5), scanMedianHeight = 10.0)
        assertEquals(listOf(10.0, 40.0, 50.0, 50.0), picked?.bbox?.toList())
    }

    @Test
    fun pickByPosition_fallsBackToFirstWithoutExpectation() {
        val occs = findAllKeyWords("MAC:", macWords)
        assertEquals(
            listOf(10.0, 10.0, 50.0, 20.0),
            pickKeyWordByPosition(occs, null, scanMedianHeight = 10.0)?.bbox?.toList(),
        )
        assertEquals(
            listOf(10.0, 10.0, 50.0, 20.0),
            pickKeyWordByPosition(occs, Pair(1.0, 4.5), scanMedianHeight = 0.0)?.bbox?.toList(),
        )
    }

    // --- assignOccurrencesGreedy ---

    @Test
    fun assignGreedy_oneToOne() {
        val assigned = assignOccurrencesGreedy(
            mapOf("A" to Pair(0.0, 1.0), "B" to Pair(0.0, 4.0), "C" to Pair(0.0, 7.0)),
            listOf(Pair(0.0, 1.1), Pair(0.0, 3.9), Pair(0.0, 7.2)),
        )
        assertEquals(mapOf("A" to 0, "B" to 1, "C" to 2), assigned)
    }

    @Test
    fun assignGreedy_moreInstancesThanOccurrences() {
        val assigned = assignOccurrencesGreedy(
            mapOf("A" to Pair(0.0, 1.0), "B" to Pair(0.0, 4.0)),
            listOf(Pair(0.0, 3.8)),
        )
        assertEquals(mapOf("B" to 0), assigned)
    }

    @Test
    fun assignGreedy_deterministicTie() {
        // Both instances equidistant from the nearest occurrence: label order decides.
        val assigned = assignOccurrencesGreedy(
            mapOf("B" to Pair(0.0, 2.0), "A" to Pair(0.0, 2.0)),
            listOf(Pair(0.0, 2.0), Pair(0.0, 99.0)),
        )
        assertEquals(0, assigned["A"])
        assertEquals(1, assigned["B"])
    }

    // --- matchKv sibling separation ---

    @Test
    fun matchKv_separatesSiblingFields() {
        val mh = medianHeight(macWords)
        val pageW = 200.0
        val pageH = 130.0
        // Bake template vectors exactly as creation does: each field's own key
        // instance → its own value bbox.
        val rows = mapOf(
            "MAC 1" to Pair(doubleArrayOf(10.0, 10.0, 50.0, 20.0), doubleArrayOf(60.0, 10.0, 160.0, 20.0)),
            "MAC 2" to Pair(doubleArrayOf(10.0, 40.0, 50.0, 50.0), doubleArrayOf(60.0, 40.0, 160.0, 50.0)),
            "MAC 3" to Pair(doubleArrayOf(10.0, 70.0, 50.0, 80.0), doubleArrayOf(60.0, 70.0, 160.0, 80.0)),
        )
        val kvVecs = rows.mapValues { (field, kb) ->
            mapOf(
                field to computeRelationVector(kb.first, kb.second, mh, pageW, pageH).toList(),
            )
        }
        val (candidates, matched) = matchKv(
            templateKvVecs = kvVecs,
            wordBoxTuples = macWords,
            medianHeight = mh,
            pageWidth = pageW,
            pageHeight = pageH,
            primaryKeyMap = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyTexts = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyPositions = mapOf(
                "MAC 1" to listOf(1.0, 1.5),
                "MAC 2" to listOf(1.0, 4.5),
                "MAC 3" to listOf(1.0, 7.5),
            ),
        )
        assertEquals(3, matched)
        for ((field, expectWi) in mapOf("MAC 1" to 1, "MAC 2" to 3, "MAC 3" to 5)) {
            val top = candidates[field]?.firstOrNull()
            assertEquals("$field bound to word ${top?.first}", expectWi, top?.first)
            assertEquals(0.0, top?.second ?: 1.0, 1e-9)
        }
        // No printed key occurrence may appear as a candidate value.
        val keyIndices = setOf(0, 2, 4)
        for (field in rows.keys) {
            val wis = (candidates[field] ?: emptyList()).map { it.first }.toSet()
            assertTrue(field, keyIndices.intersect(wis).isEmpty())
        }
    }

    /** Own-key-only kv vectors for the three MAC rows, baked as creation does. */
    private fun macKvVecs(mh: Double, pageW: Double, pageH: Double) = mapOf(
        "MAC 1" to Pair(doubleArrayOf(10.0, 10.0, 50.0, 20.0), doubleArrayOf(60.0, 10.0, 160.0, 20.0)),
        "MAC 2" to Pair(doubleArrayOf(10.0, 40.0, 50.0, 50.0), doubleArrayOf(60.0, 40.0, 160.0, 50.0)),
        "MAC 3" to Pair(doubleArrayOf(10.0, 70.0, 50.0, 80.0), doubleArrayOf(60.0, 70.0, 160.0, 80.0)),
    ).mapValues { (field, kb) ->
        mapOf(field to computeRelationVector(kb.first, kb.second, mh, pageW, pageH).toList())
    }

    @Test
    fun matchKv_translatedScanBindsByRelativeLayout() {
        // The scan frame is offset from the template frame (text-area origin
        // drift). Absolute expected positions land nearest the WRONG
        // occurrences; the keys' relative layout is translation-invariant
        // and must decide the binding.
        val dx = 40.0
        val dy = 60.0 // two row-heights down
        val shifted = macWords.map {
            WordBox(it.text, doubleArrayOf(
                it.bbox[0] + dx, it.bbox[1] + dy, it.bbox[2] + dx, it.bbox[3] + dy,
            ))
        }
        val mh = medianHeight(shifted)
        val rows = mapOf(
            "MAC 1" to Pair(doubleArrayOf(10.0 + dx, 10.0 + dy, 50.0 + dx, 20.0 + dy), doubleArrayOf(60.0 + dx, 10.0 + dy, 160.0 + dx, 20.0 + dy)),
            "MAC 2" to Pair(doubleArrayOf(10.0 + dx, 40.0 + dy, 50.0 + dx, 50.0 + dy), doubleArrayOf(60.0 + dx, 40.0 + dy, 160.0 + dx, 50.0 + dy)),
            "MAC 3" to Pair(doubleArrayOf(10.0 + dx, 70.0 + dy, 50.0 + dx, 80.0 + dy), doubleArrayOf(60.0 + dx, 70.0 + dy, 160.0 + dx, 80.0 + dy)),
        )
        val kvVecs = rows.mapValues { (field, kb) ->
            mapOf(field to computeRelationVector(kb.first, kb.second, mh, 260.0, 200.0).toList())
        }
        val (candidates, matched) = matchKv(
            templateKvVecs = kvVecs,
            wordBoxTuples = shifted,
            medianHeight = mh,
            pageWidth = 260.0,
            pageHeight = 200.0,
            primaryKeyMap = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyTexts = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            // Expected positions recorded in the UNSHIFTED template frame.
            kvKeyPositions = mapOf(
                "MAC 1" to listOf(1.0, 1.5),
                "MAC 2" to listOf(1.0, 4.5),
                "MAC 3" to listOf(1.0, 7.5),
            ),
        )
        assertEquals(3, matched)
        assertEquals(1, candidates["MAC 1"]?.firstOrNull()?.first)
        assertEquals(3, candidates["MAC 2"]?.firstOrNull()?.first)
        assertEquals(5, candidates["MAC 3"]?.firstOrNull()?.first)
    }

    @Test
    fun matchKv_readingOrderFallbackWithoutPositions() {
        // No stored expected positions (instances that never baked one): sorted
        // labels bind to occurrences top-to-bottom, so the rows still separate.
        val mh = medianHeight(macWords)
        val (candidates, matched) = matchKv(
            templateKvVecs = macKvVecs(mh, 200.0, 130.0),
            wordBoxTuples = macWords,
            medianHeight = mh,
            pageWidth = 200.0,
            pageHeight = 130.0,
            primaryKeyMap = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyTexts = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyPositions = emptyMap(),
        )
        assertEquals(3, matched)
        assertEquals(1, candidates["MAC 1"]?.firstOrNull()?.first)
        assertEquals(3, candidates["MAC 2"]?.firstOrNull()?.first)
        assertEquals(5, candidates["MAC 3"]?.firstOrNull()?.first)
    }

    @Test
    fun matchKv_missingScanOccurrenceUnbindsFarthest() {
        // Only two of the three "MAC:" keys made it into the scan: the two
        // nearest instances bind, the third votes for nothing at all (rather
        // than piling onto an occurrence a sibling already owns).
        val words = macWords.filter { it.bbox.toList() != listOf(10.0, 70.0, 50.0, 80.0) }
        val mh = medianHeight(words)
        val (candidates, matched) = matchKv(
            templateKvVecs = macKvVecs(mh, 200.0, 130.0),
            wordBoxTuples = words,
            medianHeight = mh,
            pageWidth = 200.0,
            pageHeight = 130.0,
            primaryKeyMap = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyTexts = mapOf("MAC 1" to "MAC:", "MAC 2" to "MAC:", "MAC 3" to "MAC:"),
            kvKeyPositions = mapOf(
                "MAC 1" to listOf(1.0, 1.5),
                "MAC 2" to listOf(1.0, 4.5),
                "MAC 3" to listOf(1.0, 7.5),
            ),
        )
        assertEquals(2, matched)
        assertEquals(1, candidates["MAC 1"]?.firstOrNull()?.first)
        assertEquals(3, candidates["MAC 2"]?.firstOrNull()?.first)
        assertTrue(candidates["MAC 3"].isNullOrEmpty())
    }

    // --- detection instance counts ---

    private fun rawTemplate(nMacs: Int): MutableMap<String, Any> {
        val kvKeyTexts = mutableMapOf<String, String>()
        val kv = mutableMapOf<String, MutableMap<String, List<Double>>>()
        val zeros = List(8) { 0.0 }
        for (i in 1..nMacs) {
            kvKeyTexts["MAC $i"] = "MAC:"
            kv["MAC $i"] = mutableMapOf("MAC $i" to zeros, "SN:" to zeros)
        }
        kv["Serial"] = mutableMapOf("SN:" to zeros)
        return mutableMapOf("kv_vectors_avg" to kv, "kv_key_texts" to kvKeyTexts)
    }

    private fun parse(raw: Map<String, Any>) = parseProcessedTemplate(raw, Gson())

    @Test
    fun templateKeyCounts() {
        assertEquals(mapOf("MAC:" to 3, "SN:" to 1), parse(rawTemplate(3)).detectKvKeyCounts)
    }

    @Test
    fun unbakedInstanceStillCounts() {
        val raw = rawTemplate(3)
        @Suppress("UNCHECKED_CAST")
        val kv = raw["kv_vectors_avg"] as MutableMap<String, MutableMap<String, List<Double>>>
        kv.remove("MAC 3")
        for (f in kv.keys) kv[f]?.remove("MAC 3")
        assertEquals(3, parse(raw).detectKvKeyCounts["MAC:"])
    }

    @Test
    fun legacyTemplateCountsArePresence() {
        val raw = mapOf<String, Any>(
            "kv_vectors_avg" to mapOf(
                "A" to mapOf("Qty" to listOf(0.0, 0.0), "SN:" to listOf(0.0, 0.0)),
                "B" to mapOf("SN:" to listOf(0.0, 0.0)),
            ),
        )
        assertEquals(mapOf("Qty" to 1, "SN:" to 1), parse(raw).detectKvKeyCounts)
    }

    @Test
    fun countVariantsSeparate() {
        val candidates = listOf(
            TemplateCandidate("t2", "2mac", parse(rawTemplate(2))),
            TemplateCandidate("t3", "3mac", parse(rawTemplate(3))),
        )
        val twoMacScan = macWords.filter { it.bbox.toList() != listOf(10.0, 70.0, 50.0, 80.0) }
        assertEquals("t3", detectTemplate(candidates, macWords, emptyList()).chosenId)
        assertEquals("t2", detectTemplate(candidates, twoMacScan, emptyList()).chosenId)
    }

    // --- expandText with repeated keys on one line ---

    private val repeatedRaw = "Device Label\nMAC: AA:11:22 MAC: BB:33:44 MAC: CC:55:66\nend\n"
    private val repeatedWords = listOf(
        wb("Device", 0.0, 0.0, 60.0, 10.0), wb("Label", 70.0, 0.0, 110.0, 10.0),
        wb("MAC:", 0.0, 20.0, 40.0, 30.0), wb("AA:11:22", 45.0, 20.0, 120.0, 30.0),
        wb("MAC:", 130.0, 20.0, 170.0, 30.0), wb("BB:33:44", 175.0, 20.0, 250.0, 30.0),
        wb("MAC:", 260.0, 20.0, 300.0, 30.0), wb("CC:55:66", 305.0, 20.0, 380.0, 30.0),
        wb("end", 0.0, 40.0, 30.0, 50.0),
    )

    private fun expandRepeated(word: String, wi: Int): String = expandText(
        predictedWord = word,
        wordIndex = wi,
        templateValue = "AA:BB:CC:DD:EE:FF extra",
        rawOcrText = repeatedRaw,
        wordBoxTuplesFiltered = repeatedWords,
        wordBoxTuplesUnfiltered = repeatedWords,
        keyText = "MAC:",
    ).multiWord

    @Test
    fun expand_middleValueKeepsOwnSegment() {
        assertEquals("BB:33:44", expandRepeated("BB:33:44", 5))
    }

    @Test
    fun expand_firstValueTruncatesBeforeNextKey() {
        assertEquals("AA:11:22", expandRepeated("AA:11:22", 3))
    }

    @Test
    fun expand_lastValueStripsThroughLastKey() {
        assertEquals("CC:55:66", expandRepeated("CC:55:66", 7))
    }

    @Test
    fun expand_singleKeyLineKeepsLegacyStrip() {
        val raw = "SN: ABC 123 DEF\n"
        val words = listOf(
            wb("SN:", 0.0, 0.0, 30.0, 10.0), wb("ABC", 35.0, 0.0, 65.0, 10.0),
            wb("123", 70.0, 0.0, 95.0, 10.0), wb("DEF", 100.0, 0.0, 130.0, 10.0),
        )
        val exp = expandText(
            predictedWord = "ABC",
            wordIndex = 1,
            templateValue = "ABC 123 DEF",
            rawOcrText = raw,
            wordBoxTuplesFiltered = words,
            wordBoxTuplesUnfiltered = words,
            keyText = "SN:",
        )
        assertEquals("ABC 123 DEF", exp.multiWord)
    }

    // --- dedup rrf tiebreak ---

    private fun pred(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> =
        mutableMapOf(*pairs)

    @Test
    fun dedup_rrfBreaksTextDistTie() {
        // "MAC 3" has the stronger geometric claim on word 7 despite sorting
        // last alphabetically; it must keep the word.
        val predictions = mutableMapOf(
            "MAC 1" to pred("word_index" to 7, "text_dist" to 0.4, "rrf_score" to 0.2),
            "MAC 3" to pred("word_index" to 7, "text_dist" to 0.4, "rrf_score" to 0.9),
        )
        val suggestions = mutableMapOf<String, List<MutableMap<String, Any?>>>(
            "MAC 1" to listOf(pred("word_index" to 9, "text_dist" to 0.4, "rrf_score" to 0.1)),
            "MAC 3" to listOf(pred("word_index" to 11, "text_dist" to 0.4, "rrf_score" to 0.1)),
        )
        dedupByTextScore(predictions, suggestions)
        assertEquals(7, predictions["MAC 3"]?.get("word_index"))
        assertEquals(9, predictions["MAC 1"]?.get("word_index"))
    }

    @Test
    fun dedup_textDistStillWinsOverRrf() {
        val predictions = mutableMapOf(
            "A" to pred("word_index" to 7, "text_dist" to 0.1, "rrf_score" to 0.2),
            "B" to pred("word_index" to 7, "text_dist" to 0.4, "rrf_score" to 0.9),
        )
        val suggestions = mutableMapOf<String, List<MutableMap<String, Any?>>>(
            "A" to emptyList(),
            "B" to listOf(pred("word_index" to 9, "text_dist" to 0.5, "rrf_score" to 0.1)),
        )
        dedupByTextScore(predictions, suggestions)
        assertEquals(7, predictions["A"]?.get("word_index"))
        assertEquals(9, predictions["B"]?.get("word_index"))
    }
}
