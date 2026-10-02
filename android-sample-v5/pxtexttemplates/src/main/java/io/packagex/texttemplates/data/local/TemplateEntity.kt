package io.packagex.texttemplates.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "templates")
internal data class TemplateEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val fieldCount: Int,
    val rawTemplateJson: String? = null,
    val processedTemplateJson: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    // Server-authoritative ISO-8601 timestamp from the API's `updated_at` field.
    // Used to detect when a template was edited on the server and invalidate
    // cached raw/processed blobs. Nullable so older rows keep working — they're
    // treated as needing a refresh on first reload.
    val serverUpdatedAt: String? = null,
)
