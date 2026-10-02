package io.packagex.arcount

/**
 * AR Item Count (spec 5.10): the item list says which codes are counted. Two codes match when both are valid GTINs
 * (8, 12, 13 or 14 digits ending in their check digit, a UPC-E read expanded first) and are equal as 14 digits;
 * otherwise only when their texts are equal. A code's key is what units, sections and totals carry.
 */
internal object ItemCode {
    fun key(text: String, symbology: String? = null): String {
        if (!Gtin.isGtin(text)) return text
        val n = Gtin.normalize(text, symbology)
        return if (Gtin.isValid(n)) n else text
    }

    fun key(read: Read) = key(read.text, read.symbology)
}

/** The item list in its order; a code whose key an earlier code already has is left out, so a key is listed once */
internal class ItemList(codes: Collection<String>) {
    /** (code as listed, its key) */
    val entries: List<Pair<String, String>> = codes.map { it to ItemCode.key(it) }.distinctBy { it.second }
    val keys: Set<String> = entries.mapTo(LinkedHashSet()) { it.second }

    fun isEmpty() = entries.isEmpty()
}

/** Each code's count over the closed item sections, every section with its own range (spec 5.10) */
internal class ItemTotals {
    private val low = HashMap<String, Int>()
    private val high = HashMap<String, Int>()

    fun add(byCode: Map<String, Counts>) {
        for ((key, c) in byCode) {
            low[key] = (low[key] ?: 0) + c.low
            high[key] = (high[key] ?: 0) + c.high
        }
    }

    /** One entry per listed code, in list order: the closed sections plus [open], the open section's counts */
    fun view(list: ItemList, open: Map<String, Counts>, inView: (String) -> Boolean): List<ItemCount> = list.entries.map { (code, key) ->
        val o = open[key]
        ItemCount(code, (low[key] ?: 0) + (o?.low ?: 0), (high[key] ?: 0) + (o?.high ?: 0), inView(key))
    }
}
