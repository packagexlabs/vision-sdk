package io.packagex.texttemplates.data.remote.dto

import com.google.gson.annotations.SerializedName

internal data class TemplateSummary(
    @SerializedName("template_id")
    val templateId: String,
    @SerializedName("template_name")
    val templateName: String,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
)
