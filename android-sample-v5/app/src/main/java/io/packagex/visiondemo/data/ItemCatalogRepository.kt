package io.packagex.visiondemo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-assigned item names, keyed by SKU (AR markers and results; iOS `ItemCatalog`), and the item
 * retrieval list of codes to find (iOS `DemoModel.items`, persisted as "v5.items").
 */
interface ItemCatalogRepository {
    val names: StateFlow<Map<String, String>>
    suspend fun name(sku: String, name: String)
    suspend fun remove(sku: String)
    val items: StateFlow<List<String>>
    suspend fun setItems(items: List<String>)
}

private val itemNamesKey = stringPreferencesKey("v5.pref.itemNames")
private val itemsKey = stringPreferencesKey("v5.items")

private fun decodeItems(raw: String?): List<String> =
    raw?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

private fun decodeItemNames(raw: String?): Map<String, String> =
    raw?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

@Singleton
class DataStoreItemCatalogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    appScope: CoroutineScope,
) : ItemCatalogRepository {

    override val names: StateFlow<Map<String, String>> = context.dataStore.data
        .map { decodeItemNames(it[itemNamesKey]) }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    override suspend fun name(sku: String, name: String) {
        context.dataStore.edit { p -> p[itemNamesKey] = Json.encodeToString(decodeItemNames(p[itemNamesKey]) + (sku to name)) }
    }

    override suspend fun remove(sku: String) {
        context.dataStore.edit { p -> p[itemNamesKey] = Json.encodeToString(decodeItemNames(p[itemNamesKey]) - sku) }
    }

    override val items: StateFlow<List<String>> = context.dataStore.data
        .map { decodeItems(it[itemsKey]) }
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override suspend fun setItems(items: List<String>) {
        context.dataStore.edit { p -> p[itemsKey] = Json.encodeToString(items) }
    }
}
