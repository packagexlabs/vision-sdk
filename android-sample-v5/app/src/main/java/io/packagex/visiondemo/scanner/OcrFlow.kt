package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import io.packagex.visiondemo.data.Extraction
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.OcrParser
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.RoutedExtractionException
import io.packagex.visiondemo.data.ScanError
import io.packagex.visiondemo.data.UnsupportedDocumentException
import io.packagex.visiondemo.data.VlmPrompts
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.OcrField
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Locale

/** Vision Scanner extraction, ported from iOS `DemoModel.runOCR` / `finishOCR`. Every failure is retryable. */
internal sealed interface OcrOutcome {
    data class Done(val result: ScanResult.Ocr) : OcrOutcome
    data class Failed(val title: String, val message: String, val extra: List<AlertAction> = emptyList()) : OcrOutcome
}

internal suspend fun runOcrExtraction(
    extraction: ExtractionRepository,
    bitmap: Bitmap,
    codes: List<ScannedCodeResult>,
    p: Prefs,
): OcrOutcome {
    val cloud = cloudSelected(p)
    val start = System.nanoTime()
    val x = try {
        extraction.extract(
            bitmap, codes, p.docType,
            if (cloud) Processing.Cloud else Processing.Device,
            activeModel(p)?.second ?: p.modelSize,
            p.wildCard,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ocrFailure(e, cloud, p)
    }
    return ocrResult(x, bitmap, p, cloud, (System.nanoTime() - start) / 1e9)
}

/** iOS `finishOCR`'s success branch: parse with the type actually read (the routed one for wild card). */
internal fun ocrResult(x: Extraction, bitmap: Bitmap?, p: Prefs, cloudSelected: Boolean, seconds: Double): OcrOutcome {
    // Wild card: bills of lading go to the cloud, the rest are read on-device (large).
    val cloud = if (p.wildCard) x.type == DocType.BOL else cloudSelected
    VlmPrompts.spec(x.type)?.let { return vlmResult(x, bitmap, it) }
    // Default-prompt VLM: only the model's answer (data.model_response[0]), titled by the document it read
    // (invoice, receipt, shipping label, …); the rest of the response is request bookkeeping.
    var vlmTitle: String? = null
    val r = if (x.type == DocType.VLM) {
        when (val answer = VlmPrompts.answer(x.json)) {
            is VlmPrompts.Answer.Obj -> {
                val kind = (answer.json["document_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                vlmTitle = kind?.split(" ")?.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
                val body = JsonObject(answer.json - "document_type")
                OcrParser.parse(body, body, if (vlmTitle == "Shipping Label") DocType.SL else x.type, x.json).copy(docType = x.type)
            }
            is VlmPrompts.Answer.Text -> {
                val f = OcrField("|response|0", "response", "Response", answer.text, null, null, emptyList())
                OcrResult(x.type, listOf(f), emptyList(), f, x.json)
            }
            null -> OcrResult(x.type, emptyList(), emptyList(), null, x.json)
        }
    } else {
        OcrParser.parse(x.json, x.type)
    }
    if (x.type != DocType.DC && r.fields.isEmpty() && r.tables.isEmpty()) {
        return OcrOutcome.Failed("No Text Found", OcrParser.message(x.json) ?: "Fill the frame with the label and hold still, then capture again.")
    }
    val title = when {
        x.type == DocType.DC -> "Document classified"
        p.wildCard -> "${x.type.label} (wild card)"
        else -> vlmTitle ?: x.type.label
    }
    val secs = String.format(Locale.US, "%.1f", seconds)
    val subtitle = when {
        cloud -> "Cloud · $secs s"
        else -> "On-device · ${if (p.wildCard || activeModel(p)?.second == ModelSize.Large) "large" else "micro"} · $secs s"
    }
    return OcrOutcome.Done(ScanResult.Ocr(r.copy(cloud = cloud), bitmap, title, subtitle))
}

/**
 * iOS `finishOCR`'s custom-prompt branch: only the fields of the model's answer (`data.model_response[0]`),
 * in the prompt's order, titled by the type; the rest of the response is request bookkeeping.
 */
private fun vlmResult(x: Extraction, bitmap: Bitmap?, spec: VlmPrompts.Spec): OcrOutcome {
    val answer = VlmPrompts.answer(x.json)
        ?: return OcrOutcome.Failed("Nothing recognized", OcrParser.message(x.json) ?: "VLM returned no structured data. Please try again.")
    val fields = VlmPrompts.fields(answer, spec.order, keepDocumentType = x.type == DocType.IdCard).mapIndexed { i, f ->
        OcrField("${f.section ?: ""}|${f.key}|$i", f.key, f.label, f.value, f.section, null, emptyList())
    }
    if (fields.isEmpty()) return OcrOutcome.Failed("Nothing recognized", "The VLM could not read any field. Please try again.")
    val primary = fields.firstOrNull { it.key == spec.primaryKey } ?: fields.firstOrNull { it.label == "Full Name" } ?: fields.first()
    val r = OcrResult(x.type, fields, emptyList(), primary, x.json, cloud = true)
    return OcrOutcome.Done(ScanResult.Ocr(r, bitmap, spec.title, "Cloud · VLM"))
}

/** iOS `finishOCR`'s error branch. */
internal fun ocrFailure(error: Exception, cloudSelected: Boolean, p: Prefs): OcrOutcome.Failed {
    // Wild card: the routed step decides the wording (bills of lading go to the cloud).
    val routed = error as? RoutedExtractionException
    val e = (routed?.cause as? Exception) ?: error
    val cloud = routed?.cloud ?: cloudSelected
    if (e is UnsupportedDocumentException) return ScanError.from(e).let { OcrOutcome.Failed(it.title, it.message) }
    val offline = e is UnknownHostException || e is ConnectException
    val extra = if (cloud && p.docType.onDevice && !p.wildCard) {
        listOf(AlertAction("Use On-device", PXButtonKind.Secondary, ScannerAction.UpdatePrefs(useDevice)))
    } else {
        emptyList()
    }
    return OcrOutcome.Failed(
        title = if (offline) "You're offline" else if (cloud) "Cloud request failed" else "Extraction failed",
        message = if (offline) "Cloud extraction needs a connection. Switch to on-device extraction, or retry once you are back online."
        else (e as? VisionSDKException)?.errorMessage ?: e.message ?: "Unknown error",
        extra = extra,
    )
}
