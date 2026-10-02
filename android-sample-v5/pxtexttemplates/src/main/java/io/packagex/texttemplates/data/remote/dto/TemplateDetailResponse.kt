package io.packagex.texttemplates.data.remote.dto

import com.google.gson.annotations.SerializedName

internal data class TemplateRawResponse(
    @SerializedName("name")
    val name: String,
    @SerializedName("imageFileName")
    val imageFileName: String = "",
    @SerializedName("imageWidth")
    val imageWidth: Int = 0,
    @SerializedName("imageHeight")
    val imageHeight: Int = 0,
    @SerializedName("annotations")
    val annotations: List<AnnotationDto> = emptyList(),
    @SerializedName("flexible")
    val flexible: Boolean = false
)

internal data class AnnotationDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("label")
    val label: String,
    @SerializedName("key")
    val key: String = "",
    @SerializedName("dataType")
    val dataType: String = "",
    @SerializedName("value")
    val value: String = "",
    @SerializedName("boundingBox")
    val boundingBox: BoundingBoxDto? = null,
    @SerializedName("linkedAnnotationId")
    val linkedAnnotationId: String? = null
)

internal data class BoundingBoxDto(
    @SerializedName("x")
    val x: Float,
    @SerializedName("y")
    val y: Float,
    @SerializedName("width")
    val width: Float,
    @SerializedName("height")
    val height: Float
)
