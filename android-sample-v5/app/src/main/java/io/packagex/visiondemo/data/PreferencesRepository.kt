package io.packagex.visiondemo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val docType = stringPreferencesKey("v5.pref.docType")
    val modelSize = stringPreferencesKey("v5.pref.modelSize")
    val processing = stringPreferencesKey("v5.pref.processing")
    val autoCapture = booleanPreferencesKey("v5.pref.autoCapture")
    val sound = booleanPreferencesKey("v5.pref.sound")
    val parseRecipient = booleanPreferencesKey("v5.pref.parseRecipient")
    val parseSender = booleanPreferencesKey("v5.pref.parseSender")
    val wildCard = booleanPreferencesKey("v5.pref.wildCard")
}

private fun decodePrefs(p: Preferences): Prefs = Prefs(
    multi = p[PrefKeys.multi] ?: false,
    showBoxes = p[PrefKeys.showBoxes] ?: true,
    docType = p[PrefKeys.docType]?.let { runCatching { DocType.valueOf(it) }.getOrNull() } ?: DocType.SL,
    modelSize = p[PrefKeys.modelSize]?.let { runCatching { ModelSize.valueOf(it) }.getOrNull() } ?: ModelSize.Micro,
    processing = p[PrefKeys.processing]?.let { runCatching { Processing.valueOf(it) }.getOrNull() } ?: Processing.Cloud,
    autoCapture = p[PrefKeys.autoCapture] ?: false,
    sound = p[PrefKeys.sound] ?: true,
    parseRecipient = p[PrefKeys.parseRecipient] ?: true,
    parseSender = p[PrefKeys.parseSender] ?: true,
    wildCard = p[PrefKeys.wildCard] ?: false,
)

@Singleton
class DataStorePreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PreferencesRepository {

    override val prefs: Flow<Prefs> = context.dataStore.data.map(::decodePrefs)

    override suspend fun update(transform: (Prefs) -> Prefs) {
        context.dataStore.edit { p ->
            val next = transform(decodePrefs(p))
            p[PrefKeys.multi] = next.multi
            p[PrefKeys.showBoxes] = next.showBoxes
            p[PrefKeys.docType] = next.docType.name
            p[PrefKeys.modelSize] = next.modelSize.name
            p[PrefKeys.processing] = next.processing.name
            p[PrefKeys.autoCapture] = next.autoCapture
            p[PrefKeys.sound] = next.sound
            p[PrefKeys.parseRecipient] = next.parseRecipient
            p[PrefKeys.parseSender] = next.parseSender
            p[PrefKeys.wildCard] = next.wildCard
        }
    }
}
