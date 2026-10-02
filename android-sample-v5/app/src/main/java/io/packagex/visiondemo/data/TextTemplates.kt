package io.packagex.visiondemo.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.texttemplates.sdk.PXClient
import io.packagex.texttemplates.sdk.PXConfiguration
import io.packagex.texttemplates.sdk.PXPredictionResult
import io.packagex.texttemplates.sdk.PXQuickResult
import io.packagex.texttemplates.sdk.PXTemplateInfo
import io.packagex.visiondemo.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Text Templates' SDK integration mode (iOS `TextTemplatesService.Path`). */
enum class TtPath(val label: String) { OneShot("One-Shot"), Stream("Stream") }

/** What the Text Templates mode shows: account, template cache and pool, integration mode. */
data class TtState(
    val email: String = "",
    val cached: List<PXTemplateInfo> = emptyList(),
    val loadedIds: List<String> = emptyList(),
    val syncing: Boolean = false,
    /** Blob-prefetch progress 0..1 while syncing, when the SDK reports one. */
    val syncProgress: Float? = null,
    val scanCount: Int = 0,
    val path: TtPath = TtPath.OneShot,
    /** Where the SDK sends requests (its build-time PX_BASE_URL). */
    val serverUrl: String = "",
) {
    val hasEmail get() = isValidEmail(email)
    val stream get() = path == TtPath.Stream
    val loaded get() = cached.filter { it.id in loadedIds }

    companion object {
        /** Same rule as the original's EmailSetupView (iOS `TextTemplatesService.isValid`). */
        fun isValidEmail(s: String): Boolean = s.trim().let { it.contains("@") && it.contains(".") && it.length >= 5 }
    }
}

/**
 * The Text Templates mode's use of PXTextTemplates (the `:pxtexttemplates` module), as iOS `TextTemplatesService`:
 * an account email that gates the mode (sent as X-User-Email), the template cache and the loaded pool.
 * Main-thread callers; the SDK does its own threading.
 */
interface TextTemplates {
    val state: StateFlow<TtState>
    /** The SDK client, for `PXScannerView` (Stream). Null in fakes. */
    val client: PXClient?
    /** Saves a valid email (trimmed, lowercased); false when it isn't valid. */
    fun setEmail(raw: String): Boolean
    fun signOut()
    fun setPath(p: TtPath)
    suspend fun refresh()
    /** Each returns the toast to show. */
    suspend fun sync(): String
    suspend fun load(): String
    suspend fun unload(): String
    suspend fun clearScans(): String
    suspend fun clearTemplateCache(): String
    suspend fun predict(bitmap: Bitmap): PXPredictionResult
    suspend fun repredict(scanId: String, templateId: String): PXQuickResult
    suspend fun report(scanId: String, image: Bitmap, reason: String)
}

/** For tests and previews: no account, nothing loaded. */
object NoTextTemplates : TextTemplates {
    override val state: StateFlow<TtState> = MutableStateFlow(TtState()).asStateFlow()
    override val client: PXClient? = null
    override fun setEmail(raw: String) = false
    override fun signOut() {}
    override fun setPath(p: TtPath) {}
    override suspend fun refresh() {}
    override suspend fun sync() = ""
    override suspend fun load() = ""
    override suspend fun unload() = ""
    override suspend fun clearScans() = ""
    override suspend fun clearTemplateCache() = ""
    override suspend fun predict(bitmap: Bitmap): PXPredictionResult = error("No Text Templates")
    override suspend fun repredict(scanId: String, templateId: String): PXQuickResult = error("No Text Templates")
    override suspend fun report(scanId: String, image: Bitmap, reason: String) {}
}

@Singleton
class TextTemplatesService @Inject constructor(@ApplicationContext context: Context) : TextTemplates {
    /** The email is stored on this device only (iOS keeps it in the Keychain). */
    private val prefs = context.getSharedPreferences("text_templates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        TtState(email = prefs.getString(KEY_EMAIL, null).orEmpty(), serverUrl = io.packagex.texttemplates.BuildConfig.PX_BASE_URL),
    )
    override val state: StateFlow<TtState> = _state.asStateFlow()

    /** One client for the app run; the email is read on every request, so it never has to be rebuilt. */
    override val client = PXClient(
        context,
        PXConfiguration(apiKey = BuildConfig.MOBILE_API_KEY),
        userEmailProvider = { _state.value.email.ifBlank { null } },
    )

    override fun setEmail(raw: String): Boolean {
        val e = raw.trim().lowercase()
        if (!TtState.isValidEmail(e)) return false
        prefs.edit().putString(KEY_EMAIL, e).apply()
        _state.update { it.copy(email = e) }
        return true
    }

    override fun signOut() {
        prefs.edit().remove(KEY_EMAIL).apply()
        _state.update { it.copy(email = "") }
    }

    override fun setPath(p: TtPath) = _state.update { it.copy(path = p) }

    override suspend fun refresh() {
        val cached = runCatching { client.getTemplates() }.getOrDefault(_state.value.cached)
        _state.update { it.copy(cached = cached, loadedIds = client.getLoadedTemplateIds(), scanCount = client.getScanCount()) }
    }

    override suspend fun sync(): String {
        if (!_state.value.hasEmail) return "Set up your account first"
        _state.update { it.copy(syncing = true) }
        return try {
            val r = client.syncTemplates { done, total ->
                _state.update { it.copy(syncProgress = if (total > 0) done.toFloat() / total else null) }
            }
            "${r.total} templates (${r.added} new, ${r.refreshed} refreshed, ${r.removed} removed)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        } finally {
            _state.update { it.copy(syncing = false, syncProgress = null) }
            refresh()
        }
    }

    override suspend fun load(): String {
        if (!_state.value.hasEmail) return "Set up your account first"
        if (_state.value.cached.isEmpty()) sync()
        return try {
            client.unload()
            client.load(null)
            refresh()
            "Loaded ${_state.value.loadedIds.size} templates"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refresh()
            "Load failed: ${e.message}"
        }
    }

    override suspend fun unload(): String { client.unload(); refresh(); return "Pool unloaded (disk cache kept)" }
    override suspend fun clearScans(): String { client.clearScanCache(); refresh(); return "Scan cache cleared" }
    override suspend fun clearTemplateCache(): String { client.clearTemplateCache(); refresh(); return "Template cache cleared" }

    override suspend fun predict(bitmap: Bitmap) = client.predict(bitmap).also { refresh() }
    override suspend fun repredict(scanId: String, templateId: String) = client.repredict(scanId, templateId)
    override suspend fun report(scanId: String, image: Bitmap, reason: String) = client.report(scanId, image, reason)

    private companion object {
        const val KEY_EMAIL = "user_email"
    }
}
