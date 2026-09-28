package io.packagex.visiondemo.document

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentPagesTest {
    private fun page(i: Int) = DocumentPage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), index = i)

    @Test fun retakeAfterExportKeepsPages() {
        val d = DocumentPages(); d.add(page(1)); d.add(page(2)); d.exported = true
        d.retake(dropLast = true); d.add(page(3))
        assertEquals(listOf(1, 3), d.pages.map { it.index })
    }

    @Test fun captureAfterExportStartsNewDocument() {
        val d = DocumentPages(); d.add(page(1)); d.exported = true; d.add(page(2))
        assertEquals(listOf(2), d.pages.map { it.index })
    }

    @Test fun addPageKeepsEveryPage() {
        val d = DocumentPages(); d.add(page(1)); d.exported = true
        d.retake(dropLast = false); d.add(page(2))
        assertEquals(listOf(1, 2), d.pages.map { it.index }); assertFalse(d.exported)
    }

    @Test fun resetDropsPagesAndExport() {
        val d = DocumentPages(); d.add(page(1)); d.exported = true; d.reset()
        assertEquals(emptyList<Int>(), d.pages.map { it.index }); assertFalse(d.exported)
    }
}
