package io.packagex.visiondemo.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.packagex.visiondemo.ar.OverlayRules
import io.packagex.visiondemo.ar.PinRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the settings are stored ([encodePrefs], [decodePrefs]) */
class PreferencesRepositoryTest {
    private val pinRules = stringPreferencesKey("v5.pref.arPinRules")
    private val overlayRules = stringPreferencesKey("v5.pref.arOverlayRules")
    private val readBoost = booleanPreferencesKey("v5.pref.arReadBoost")
    private val pinRefine = booleanPreferencesKey("v5.pref.arPinRefine")

    @Test fun theRuleFlagsAreStoredOnlyOnceChanged() {
        val p = mutablePreferencesOf()
        // Another toggle: the rule flags stay unset, so they follow their defaults
        encodePrefs(p, decodePrefs(p).copy(sound = false))
        assertFalse(decodePrefs(p).sound)
        assertFalse(p.contains(pinRules)); assertFalse(p.contains(overlayRules)); assertFalse(p.contains(readBoost)); assertFalse(p.contains(pinRefine))
        assertEquals(PinRules.ANDROID, decodePrefs(p).arPinRules); assertTrue(decodePrefs(p).arReadBoost); assertTrue(decodePrefs(p).arPinRefine)
        // Chosen, and chosen back: stored either way
        encodePrefs(p, decodePrefs(p).copy(arPinRules = PinRules.IOS, arOverlayRules = OverlayRules.IOS))
        assertEquals(PinRules.IOS, decodePrefs(p).arPinRules); assertEquals(OverlayRules.IOS, decodePrefs(p).arOverlayRules)
        encodePrefs(p, decodePrefs(p).copy(arPinRules = PinRules.ANDROID))
        assertEquals("ANDROID", p[pinRules]); assertEquals("IOS", p[overlayRules])
        encodePrefs(p, decodePrefs(p).copy(multi = true))
        assertEquals("ANDROID", p[pinRules])
        assertFalse(p.contains(readBoost))
        encodePrefs(p, decodePrefs(p).copy(arReadBoost = false))
        assertEquals(false, p[readBoost]); assertFalse(decodePrefs(p).arReadBoost)
        assertFalse(p.contains(pinRefine))
        encodePrefs(p, decodePrefs(p).copy(arPinRefine = false))
        assertEquals(false, p[pinRefine]); assertFalse(decodePrefs(p).arPinRefine)
    }
}
