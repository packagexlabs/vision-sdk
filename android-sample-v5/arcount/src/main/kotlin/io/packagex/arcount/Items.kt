package io.packagex.arcount

/**
 * AR Item Count (spec 5.10): the item list says which codes are counted. Two codes match when both are valid GTINs
 * (8, 12, 13 or 14 digits ending in their check digit, a UPC-E read expanded first) and are equal as 14 digits;
 * otherwise only when their texts are equal. A code's key is what units, sections and totals carry.
 */
object ItemCode {
    fun key(text: String, symbology: String? = null): String {
        if (!Gtin.isGtin(text)) return text
        val n = Gtin.normalize(text, symbology)
        return if (Gtin.isValid(n)) n else text
    }

    fun key(read: Read) = key(read.text, read.symbology)
}

/** One listed code and the keys its reads may carry: its own, and for 8 digits that are a valid UPC-E, the UPC-A one */
internal class ItemEntry(val code: String, val keys: Set<String>)

/**
 * The item list in its order (ruling R4: an 8-digit code also matches its UPC-E reads, expanded to UPC-A then 14
 * digits). A key belongs to the first code that has it; a code left with no key of its own is left out.
 */
internal class ItemList(codes: Collection<String>) {
    val entries: List<ItemEntry>
    val keys: Set<String>

    init {
        val claimed = LinkedHashSet<String>()
        entries = codes.mapNotNull { code ->
            val own = aliases(code).filter { claimed.add(it) }.toSet()
            if (own.isEmpty()) null else ItemEntry(code, own)
        }
        keys = claimed
    }

    fun isEmpty() = entries.isEmpty()

    private fun aliases(code: String): List<String> {
        val upcE = if (code.length == 8 && code[0] in "01" && Gtin.isGtin(code)) Gtin.normalize(code, "UPC_E").takeIf { Gtin.isValid(it) } else null
        return listOfNotNull(ItemCode.key(code), upcE)
    }
}

/**
 * Each key's count over the closed item sections, every section with its own range (spec 5.10); an ABANDONED one adds
 * [0, its high] (ruling R3), so a failed resume can widen a range but never raise a definite count.
 */
internal class ItemTotals {
    private val low = HashMap<String, Int>()
    private val high = HashMap<String, Int>()

    fun add(byCode: Map<String, Counts>, abandoned: Boolean = false) {
        for ((key, c) in byCode) {
            if (!abandoned) low[key] = (low[key] ?: 0) + c.low
            high[key] = (high[key] ?: 0) + c.high
        }
    }

    /** One entry per listed code, in list order: the closed sections plus [open], the open section's counts, over its keys */
    fun view(list: ItemList, open: Map<String, Counts>, inView: (String) -> Boolean): List<ItemCount> = list.entries.map { e ->
        ItemCount(
            e.code,
            e.keys.sumOf { (low[it] ?: 0) + (open[it]?.low ?: 0) },
            e.keys.sumOf { (high[it] ?: 0) + (open[it]?.high ?: 0) },
            e.keys.any(inView),
        )
    }
}
