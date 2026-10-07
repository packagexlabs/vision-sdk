package io.packagex.visiondemo.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.ar.OverlayRules
import io.packagex.visiondemo.ar.PinRules
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.Processing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class Prefs(
    val multi: Boolean = false,
    val showBoxes: Boolean = true,
    // iOS DemoModel.swift:64: the hint line above the camera.
    val showHints: Boolean = true,
    val docType: DocType = DocType.SL,
    val modelSize: ModelSize = ModelSize.Micro,
    val processing: Processing = Processing.Cloud,
    val autoCapture: Boolean = false,
    val sound: Boolean = true,
    // iOS DemoModel.swift:67-68 default both to true.
    val parseRecipient: Boolean = true,
    val parseSender: Boolean = true,
    // iOS DemoModel.swift:66.
    val wildCard: Boolean = false,
    // AR diagnostics (Settings › Advanced › "AR traces"): frame/read/engine traces in app storage; off by default (spec 5.7).
    val arTrace: Boolean = false,
    // AR blur pre-skip (Settings › Advanced): on by default (spec 5.6); measurement runs turn it off.
    val arBlurSkip: Boolean = true,
    // The drift plan's flags below default to its new behaviour on purpose, ahead of its §5 device gates; each arm is
    // switched back in Settings › Advanced, and only a flag someone changed is stored ([encodePrefs]).
    // AR outlines (Settings › Advanced, drift plan Phase 1): carried to the frame shown by default; IOS draws them where read.
    val arOverlayRules: OverlayRules = OverlayRules.ANDROID,
    // AR far-safe outline depth (Settings › Advanced): codes other than EAN/UPC carried at 0.8 m; off by default.
    val arOutlineFarSafe: Boolean = false,
    // AR pins (Settings › Advanced, drift plan Phases 2-4): seeded by the nearest valid hit, with the §3.4 identity rules,
    // by default; IOS takes a plane first, keeps the iOS identity rules and never refines.
    val arPinRules: PinRules = PinRules.ANDROID,
    // AR pin refinement (Settings › Advanced, drift plan Phase 4): under the Android rules each claim refines its pin, by
    // default; off freezes pins at birth (Phase 3), for A/B runs in one session.
    val arPinRefine: Boolean = true,
    // AR read-rate boost (Settings › Advanced, drift plan P2c): on by default; off keeps the counter's refresh schedule.
    val arReadBoost: Boolean = true,
)

interface PreferencesRepository {
    val prefs: Flow<Prefs>
    suspend fun update(transform: (Prefs) -> Prefs)
}

/** Single DataStore for the app; shared with [ItemCatalogRepository] (one `preferencesDataStore` delegate per name). */
internal val Context.dataStore by preferencesDataStore(name = "v5")

private object PrefKeys {
    val multi = booleanPreferencesKey("v5.pref.multi")
    val showBoxes = booleanPreferencesKey("v5.pref.showBoxes")
    val showHints = booleanPreferencesKey("v5.pref.showHints")
    val docType = stringPreferencesKey("v5.pref.docType")
    val modelSize = stringPreferencesKey("v5.pref.modelSize")
    val processing = stringPreferencesKey("v5.pref.processing")
    val autoCapture = booleanPreferencesKey("v5.pref.autoCapture")
    val sound = booleanPreferencesKey("v5.pref.sound")
    val parseRecipient = booleanPreferencesKey("v5.pref.parseRecipient")
    val parseSender = booleanPreferencesKey("v5.pref.parseSender")
    val wildCard = booleanPreferencesKey("v5.pref.wildCard")
    val arTrace = booleanPreferencesKey("v5.pref.arTrace")
    val arBlurSkip = booleanPreferencesKey("v5.pref.arBlurSkip")
    val arOverlayRules = stringPreferencesKey("v5.pref.arOverlayRules")
    val arOutlineFarSafe = booleanPreferencesKey("v5.pref.arOutlineFarSafe")
    val arPinRules = stringPreferencesKey("v5.pref.arPinRules")
    val arPinRefine = booleanPreferencesKey("v5.pref.arPinRefine")
    val arReadBoost = booleanPreferencesKey("v5.pref.arReadBoost")
}

internal fun decodePrefs(p: Preferences): Prefs = Prefs(
    multi = p[PrefKeys.multi] ?: false,
    showBoxes = p[PrefKeys.showBoxes] ?: true,
    showHints = p[PrefKeys.showHints] ?: true,
    docType = p[PrefKeys.docType]?.let { runCatching { DocType.valueOf(it) }.getOrNull() } ?: DocType.SL,
    modelSize = p[PrefKeys.modelSize]?.let { runCatching { ModelSize.valueOf(it) }.getOrNull() } ?: ModelSize.Micro,
    processing = p[PrefKeys.processing]?.let { runCatching { Processing.valueOf(it) }.getOrNull() } ?: Processing.Cloud,
    autoCapture = p[PrefKeys.autoCapture] ?: false,
    sound = p[PrefKeys.sound] ?: true,
    parseRecipient = p[PrefKeys.parseRecipient] ?: true,
    parseSender = p[PrefKeys.parseSender] ?: true,
    wildCard = p[PrefKeys.wildCard] ?: false,
    arTrace = p[PrefKeys.arTrace] ?: false,
    arBlurSkip = p[PrefKeys.arBlurSkip] ?: true,
    arOverlayRules = p[PrefKeys.arOverlayRules]?.let { runCatching { OverlayRules.valueOf(it) }.getOrNull() } ?: OverlayRules.ANDROID,
    arOutlineFarSafe = p[PrefKeys.arOutlineFarSafe] ?: false,
    arPinRules = p[PrefKeys.arPinRules]?.let { runCatching { PinRules.valueOf(it) }.getOrNull() } ?: PinRules.ANDROID,
    arPinRefine = p[PrefKeys.arPinRefine] ?: true,
    arReadBoost = p[PrefKeys.arReadBoost] ?: true,
)

@Singleton
class DataStorePreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PreferencesRepository {

    override val prefs: Flow<Prefs> = context.dataStore.data.map(::decodePrefs)

    override suspend fun update(transform: (Prefs) -> Prefs) {
        context.dataStore.edit { p -> encodePrefs(p, transform(decodePrefs(p))) }
    }
}

/**
 * Stores [next] in [p]. The drift plan's rule flags are stored only once changed, so a flag nobody chose keeps
 * following its default, should a device gate change it.
 */
internal fun encodePrefs(p: MutablePreferences, next: Prefs) {
    val cur = decodePrefs(p)
    p[PrefKeys.multi] = next.multi
    p[PrefKeys.showBoxes] = next.showBoxes
    p[PrefKeys.showHints] = next.showHints
    p[PrefKeys.docType] = next.docType.name
    p[PrefKeys.modelSize] = next.modelSize.name
    p[PrefKeys.processing] = next.processing.name
    p[PrefKeys.autoCapture] = next.autoCapture
    p[PrefKeys.sound] = next.sound
    p[PrefKeys.parseRecipient] = next.parseRecipient
    p[PrefKeys.parseSender] = next.parseSender
    p[PrefKeys.wildCard] = next.wildCard
    p[PrefKeys.arTrace] = next.arTrace
    p[PrefKeys.arBlurSkip] = next.arBlurSkip
    if (next.arOverlayRules != cur.arOverlayRules) p[PrefKeys.arOverlayRules] = next.arOverlayRules.name
    p[PrefKeys.arOutlineFarSafe] = next.arOutlineFarSafe
    if (next.arPinRules != cur.arPinRules) p[PrefKeys.arPinRules] = next.arPinRules.name
    if (next.arPinRefine != cur.arPinRefine) p[PrefKeys.arPinRefine] = next.arPinRefine
    if (next.arReadBoost != cur.arReadBoost) p[PrefKeys.arReadBoost] = next.arReadBoost
}
