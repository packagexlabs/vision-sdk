# Consumer ProGuard rules for the PXTextTemplates SDK.
#
# Several types are (de)serialized reflectively by Gson — the host serializes the
# public result/wire types for the RN/Flutter bridge, and the SDK round-trips the
# scan cache to disk. R8/minify in a consumer app would otherwise rename those
# field/enum names and break the JSON shape (and the scan repredict/report cache).
# Keep the field + enum names on all of them.

# Keep @SerializedName metadata + generic signatures (needed for TypeToken).
-keepattributes *Annotation*, Signature

# Public SDK types the host Gson-serializes for the RN/Flutter wire
# (PXField, PXBarcode, PXPredictionResult, PXRegionOfInterest, PXQuickResult, PXTemplateInfo,
#  PXTemplateSyncResult, PXDetection, PXTemplateMatch, PXScanEventPayload,
#  PXGuidancePayload, PXErrorPayload, …) AND the internal scan-cache records
#  (ScanRecord / ScanCapture) round-tripped by PXScanStore.
-keepclassmembers class io.packagex.texttemplates.sdk.** {
    <fields>;
}
# @SerializedName enums (e.g. PXRegionOfInterest.Source "default"/"host").
-keepclassmembers enum io.packagex.texttemplates.sdk.** {
    *;
}

# Retained extraction models serialized inside a scan record (OcrFrameResult,
# OcrBlock/Line/Word, BoundingBox, BarcodeFrameResult, DetectedBarcode).
-keepclassmembers class io.packagex.texttemplates.extraction.models.** {
    <fields>;
}
# RectD (previewRect / innerBox) also lives in a scan record.
-keepclassmembers class io.packagex.texttemplates.prediction.RectD {
    <fields>;
}

# Wire/result DTOs from the networking layer (unchanged).
-keepclassmembers class io.packagex.texttemplates.data.remote.dto.** {
    <fields>;
}

# R8 full mode (the AGP 8 default; the Label Scanner sample pins it): Gson creates the types above
# reflectively, so R8 sees no constructor call and may drop or merge the classes themselves (their
# fields then read as null). Keep the classes, not only their fields.
-keep class io.packagex.texttemplates.data.remote.dto.** { *; }
-keep class io.packagex.texttemplates.sdk.** { *; }
-keep class io.packagex.texttemplates.extraction.models.** { *; }
-keep class io.packagex.texttemplates.prediction.RectD { *; }

# Generic TypeToken subclasses (template cache and report payload parsing) need their Signature;
# Gson only ships these rules from 2.11 on, and Retrofit's converter-gson 2.11 brings 2.10.1.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
