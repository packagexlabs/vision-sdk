// Local Models build (`localRelease`): fetches every on-device OCR model the sample offers at build time, so
// the APK ships them and the app installs them with ModelManager.installBundledModel() instead of downloading.
//
// Same source as ModelManager.downloadModel(): the SDK's `v1/sdk/connect` call (API key from secrets.properties
// / env) returns the model version and a download link encrypted for the SDK; the link is decrypted the way
// Model.download() does it, using the environment key pair from the vision-sdk-android checkout. The payload is
// stored exactly as served (still encrypted; only the SDK can unwrap its key on the device) next to a manifest.
// Each download is checked against the server's MD5 of the decrypted model (`_rq._mv._h`). Payloads are cached
// in build/bundled-models (git-ignored) and only fetched again when the server's version changes.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.DigestInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** (connect `_t`, `_s`) for every model file the sample's Models sheet needs: BOL and IL also load the KV micro model. */
val bundledModels = listOf(
    "shipping_label" to "micro", "shipping_label" to "large",
    "bill_of_lading" to "large", "item_label" to "large",
    "document_classification" to "micro", "document_classification" to "large",
    "key_values" to "micro",
)

val fetchBundledModels by tasks.registering {
    description = "Fetches the on-device OCR models bundled into the localRelease APK."
    val outDir = rootProject.layout.buildDirectory.dir("bundled-models")
    outputs.dir(outDir)
    outputs.upToDateWhen { false }   // the connect calls decide; unchanged payloads aren't downloaded again
    // Set by build.gradle.kts, which owns the secrets and the SDK checkout path.
    val env = (project.extra["bundledModels.env"] as String).uppercase()
    val apiKey = project.extra["bundledModels.apiKey"] as String
    val sdkSource = project.extra["bundledModels.sdkSource"] as File
    doLast {
        if (apiKey.isBlank()) throw GradleException("localRelease bundles the models: set ${env}_API_KEY in secrets.properties or the environment")
        val dir = outDir.get().asFile.resolve("bundled_models").apply { mkdirs() }
        val manifestFile = dir.resolve("manifest.json")
        @Suppress("UNCHECKED_CAST")
        val cached = (if (manifestFile.isFile) JsonSlurper().parse(manifestFile) as List<Map<String, String>> else emptyList())
        val (sdkId, privateKey) = environmentKeys(sdkSource, env)
        val base = if (env == "PRODUCTION") "https://api.packagex.io/" else "https://${env.lowercase()}--api.packagex.io/"
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val manifest = bundledModels.map { (cls, size) ->
            val body = JsonOutput.toJson(mapOf("_i" to sdkId, "_d" to "label-scanner-apk-build", "_f" to "native", "_p" to "android",
                "_m" to mapOf("_t" to cls, "_s" to size, "_d" to true, "_c" to "image", "_l" to "Latn")))
            val res = http.send(HttpRequest.newBuilder(URI.create(base + "v1/sdk/connect")).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("x-api-key", apiKey).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) throw GradleException("connect for $cls/$size failed: HTTP ${res.statusCode()}")
            @Suppress("UNCHECKED_CAST")
            val rq = ((JsonSlurper().parseText(res.body()) as Map<String, Any?>)["data"] as Map<String, Any?>)["_rq"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val mv = rq["_mv"] as Map<String, String>
            @Suppress("UNCHECKED_CAST") val d = rq["_d"] as Map<String, String>
            val entry = mapOf("modelClass" to cls, "modelSize" to size, "version" to mv.getValue("_v"), "modelId" to rq["_i"] as String,
                "modelVersionId" to mv.getValue("_i"), "key" to d.getValue("_k"), "file" to "${cls}_$size.bin")
            val file = dir.resolve(entry.getValue("file"))
            // The wrapped key `_k` differs on every connect (randomised RSA padding) but unwraps to the same
            // payload key, so a cached file is matched by version and keeps the key it was fetched with.
            val hit = cached.firstOrNull { it["file"] == entry["file"] && it["version"] == entry["version"] }
            if (file.isFile && hit != null) {
                logger.lifecycle("Bundled model $cls/$size ${entry["version"]}: cached")
                return@map hit
            }
            // Model.download(): RSA-unwrap the payload key, then AES/CBC-decrypt the link ("<data hex>--<iv hex>").
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, privateKey) }
            val wrapped = d.getValue("_k").hexToBytes()
            val key = ByteArrayOutputStream().apply { wrapped.toList().chunked(512).forEach { write(rsa.doFinal(it.toByteArray())) } }.toByteArray()
            val (data, iv) = d.getValue("_u").split("--", limit = 2)
            val url = String(aesCbc(key, iv.hexToBytes()).doFinal(data.hexToBytes()))
            val part = dir.resolve("${entry["file"]}.part")
            logger.lifecycle("Bundled model $cls/$size ${entry["version"]}: downloading")
            val dl = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
            if (dl.statusCode() != 200) throw GradleException("download of $cls/$size failed: HTTP ${dl.statusCode()}")
            // The payload is a 16-byte IV, then AES/CBC; `_h` is the MD5 of what it decrypts to.
            val md5 = MessageDigest.getInstance("MD5")
            part.inputStream().buffered().use { input ->
                val ivBytes = input.readNBytes(16)
                DigestInputStream(CipherInputStream(input, aesCbc(key, ivBytes)), md5).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            }
            val got = md5.digest().joinToString("") { "%02x".format(it) }
            if (got != mv["_h"]) { part.delete(); throw GradleException("$cls/$size failed its integrity check (MD5 $got, server ${mv["_h"]})") }
            file.delete(); part.renameTo(file)
            entry
        }
        dir.listFiles()!!.filter { f -> f.name != manifestFile.name && manifest.none { it["file"] == f.name } }.forEach { it.delete() }
        manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
    }
}

fun String.hexToBytes() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

fun aesCbc(key: ByteArray, iv: ByteArray): Cipher =
    Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

/** The SDK id and RSA private key of [env], read from the SDK's Environment (VisionSDK.kt) the way Model.download() decodes them. */
fun environmentKeys(sdkSource: File, env: String): Pair<String, java.security.PrivateKey> {
    if (!sdkSource.isFile) throw GradleException("localRelease needs the SDK source at $sdkSource (pass -PvisionSdkAndroidDir=<vision-sdk-android checkout>)")
    val m = Regex("""data object $env : Environment\(\s*pub = "[^"]*",\s*pri = "([^"]*)",\s*sdkId = "([^"]*)"""")
        .find(sdkSource.readText()) ?: throw GradleException("Environment $env not found in $sdkSource")
    val pri = m.groupValues[1].replace("\\\\", "\\")
    // Model.scrambleString(mas, 5): reverse each 5-character run. The result is the base64 AES key for pub/pri.
    val mas = "ThjgMuEL3D4yURfF9Q8a2debs5P6KzcS".chunked(5).joinToString("") { it.reversed() }
    val (iv, data) = pri.split("\\|/", limit = 2)
    val b64 = Base64.getDecoder()
    val pem = String(aesCbc(b64.decode(mas), b64.decode(iv)).doFinal(b64.decode(data)))
    return m.groupValues[2] to KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(b64.decode(pem)))
}
