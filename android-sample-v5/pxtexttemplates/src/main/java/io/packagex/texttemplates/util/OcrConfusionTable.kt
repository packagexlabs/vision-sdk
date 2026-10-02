package io.packagex.texttemplates.util

/**
 * Common OCR confusion pairs used for consensus normalization.
 * Maps commonly confused character sequences to their canonical form.
 */
internal object OcrConfusionTable {

    private val confusionPairs: Map<String, String> = mapOf(
        "O" to "0",
        "o" to "0",
        "l" to "1",
        "I" to "1",
        "|" to "1",
        "S" to "5",
        "s" to "5",
        "B" to "8",
        "Z" to "2",
        "z" to "2",
        "G" to "6",
        "g" to "9",
        "rn" to "m",
        "cl" to "d",
        "cI" to "d",
    )

    /**
     * Normalize a word by replacing commonly confused characters.
     * Used when no majority vote is reached during consensus.
     */
    fun normalize(word: String): String {
        var normalized = word
        // Apply multi-char replacements first (longer patterns)
        confusionPairs.entries
            .sortedByDescending { it.key.length }
            .forEach { (confused, canonical) ->
                normalized = normalized.replace(confused, canonical)
            }
        return normalized
    }

    /**
     * Check if two words are equivalent after normalization.
     */
    fun areEquivalent(word1: String, word2: String): Boolean {
        return normalize(word1) == normalize(word2)
    }
}
