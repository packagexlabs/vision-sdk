package io.packagex.visiondemo.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test fun tokensMatchIos() {
        assertEquals(Color(0xFF7420E2), PX.Purple); assertEquals(Color(0xFF47EAE2), PX.Neon)
        assertEquals(Color(0xFF101023), PX.Ink); assertEquals(Color(0xFF983B3B), PX.RedText)
        assertEquals(PX.Ink.copy(alpha = 0.5f), PX.Glass)
    }
}
