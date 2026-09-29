package io.packagex.visiondemo.document

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentMemoryTest {
    @Test fun outputLongEdgeIsCappedAt2400() {
        assertEquals(2400 to 1800, pageOutputSize(4000, 3000))
        assertEquals(1800 to 2400, pageOutputSize(3000, 4000))
        assertEquals(1000 to 800, pageOutputSize(1000, 800))   // never upscaled
    }

    private fun session() = DocumentSession(RuntimeEnvironment.getApplication())
    private fun bitmap() = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)

    @Test fun straightenedPageDropsTheRawCrop() {
        DocumentEnhancer.useNative = false
        val crop = bitmap()
        val straight = bitmap()
        val page = DocumentPage(crop, 1)
        session().finish(page, DewarpResult(straight, corrected = true, deviation = 0.1, modelMs = 0, resampleMs = 0))
        assertSame(straight, page.page); assertSame(straight, page.original); assertNotNull(page.enhanced)
    }

    @Test fun flatPageKeepsItsCrop() {
        DocumentEnhancer.useNative = false
        val crop = bitmap()
        val page = DocumentPage(crop, 1)
        session().finish(page, DewarpResult(crop, corrected = false, deviation = 0.0, modelMs = 0, resampleMs = 0))
        assertSame(crop, page.page); assertSame(crop, page.original); assertNotNull(page.enhanced)
    }
}
