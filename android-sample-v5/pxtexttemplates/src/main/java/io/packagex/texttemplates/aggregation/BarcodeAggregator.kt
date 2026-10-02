package io.packagex.texttemplates.aggregation

import io.packagex.texttemplates.aggregation.models.AggregatedBarcode
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult

internal class BarcodeAggregator constructor() {

    /**
     * Deduplicates barcodes across frames by (format + data).
     * Only includes barcodes seen in >= 2 frames.
     * Full implementation in Step 8.
     */
    fun aggregate(frames: List<BarcodeFrameResult>): List<AggregatedBarcode> {
        val countMap = mutableMapOf<Pair<String, String>, Int>()

        frames.forEach { frame ->
            frame.barcodes.forEach { barcode ->
                val key = barcode.format to barcode.data
                countMap[key] = (countMap[key] ?: 0) + 1
            }
        }

        return countMap
            .filter { it.value >= 1 }
            .map { (key, count) ->
                AggregatedBarcode(
                    data = key.second,
                    format = key.first,
                    frameCount = count
                )
            }
    }
}
