package io.packagex.arcount

/** GTIN normalisation (spec 5.1): UPC-A, EAN-13, EAN-8, GTIN-14 and expanded UPC-E all compare as 14 digits */
object Gtin {
    private val lengths = setOf(8, 12, 13, 14)

    /** Whether [text] is an 8, 12, 13 or 14 digit number */
    fun isGtin(text: String) = text.length in lengths && text.all { it in '0'..'9' }

    /**
     * [text] as a 14-digit GTIN; a UPC-E symbol (by [symbology]) is expanded to UPC-A first. A payload that is not a
     * GTIN is returned unchanged, so it still works as a key.
     */
    fun normalize(text: String, symbology: String? = null): String {
        if (!isGtin(text)) return text
        val digits = if (text.length == 8 && isUpcE(symbology)) expandUpcE(text) else text
        return digits.padStart(14, '0')
    }

    private fun isUpcE(symbology: String?) = symbology?.lowercase()?.filter { it.isLetterOrDigit() } == "upce"

    /** UPC-E (number system, six digits, check digit) to its UPC-A */
    private fun expandUpcE(e: String): String {
        val n = e[0]
        val d = e.substring(1, 7)
        val check = e[7]
        val body = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return "$n$body$check"
    }
}
