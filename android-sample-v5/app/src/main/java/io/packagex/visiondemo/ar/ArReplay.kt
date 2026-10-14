package io.packagex.visiondemo.ar

import io.packagex.arcount.Read
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Record and replay of an AR Item Count session (ARCore Recording and Playback). A recording is ARCore's MP4 (its CPU
 * image, IMU and this track) with a sidecar ([RecordingInfo]); the track carries everything the counter and the pins
 * take from the barcode engine, so a replay runs them on the recorded session with no camera and no engine: each
 * sample is one ARCore frame's ([TrackSample.frameCameraNs]) and holds the reads batches that reached the app while that
 * frame was the newest, and any change of the item list.
 */
internal val READS_TRACK: UUID = UUID.fromString("5f4c2a1e-8d3b-4f6a-9c2e-7a1b0d3e6f90")
internal const val READS_TRACK_MIME = "application/x-arcount-reads"
private const val TRACK_VERSION = 1
private const val ENTRY_ITEMS = 1
private const val ENTRY_READS = 2

/** What the track holds, in the order it reached the app */
internal sealed interface TrackEntry {
    data class Items(val codes: Set<String>) : TrackEntry

    /** One engine batch as it was posted to the mapper, and the Camera2 result of its capture (null: it had not come) */
    data class Reads(val event: ArEvent.Reads, val meta: CaptureMeta?) : TrackEntry
}

/** One sample of the track: the entries written on the frame whose Android camera timestamp was [frameCameraNs] */
internal data class TrackSample(val frameCameraNs: Long, val entries: List<TrackEntry>)

internal fun encodeSample(sample: TrackSample): ByteArray {
    val bytes = ByteArrayOutputStream(1024)
    DataOutputStream(bytes).use { out ->
        out.writeByte(TRACK_VERSION)
        out.writeLong(sample.frameCameraNs)
        out.writeInt(sample.entries.size)
        for (e in sample.entries) when (e) {
            is TrackEntry.Items -> {
                out.writeByte(ENTRY_ITEMS)
                out.writeInt(e.codes.size)
                e.codes.forEach(out::writeUTF)
            }
            is TrackEntry.Reads -> {
                out.writeByte(ENTRY_READS)
                out.writeLong(e.event.timestampNs)
                writeReads(out, e.event.reads)
                writeReads(out, e.event.tracked)
                val s = e.event.stats
                out.writeBoolean(s != null)
                if (s != null) {
                    out.writeFloat(s.fps); out.writeFloat(s.prepareMs); out.writeFloat(s.detectMs); out.writeFloat(s.decodeMs)
                    out.writeInt(s.barcodes); out.writeInt(s.decoded); out.writeFloat(s.scanMs); out.writeLong(s.droppedImages)
                    out.writeLong(s.refreshAfterMs)
                    val p = s.pipe
                    out.writeLong(p.lumaFrames); out.writeLong(p.lumaCopyNs); out.writeLong(p.lumaScaleNs); out.writeLong(p.lumaDropped)
                    out.writeLong(p.blurSkipped)
                }
                val m = e.meta
                out.writeBoolean(m != null)
                if (m != null) {
                    out.writeLong(m.sensorTimestampNs); out.writeLong(m.exposureNs); out.writeInt(m.sensitivity)
                    out.writeLong(m.rollingShutterSkewNs); out.writeInt(m.aeCompensation); out.writeUTF(m.fpsRange)
                    out.writeInt(m.afState); out.writeFloat(m.focusDiopters)
                }
            }
        }
    }
    return bytes.toByteArray()
}

private fun writeReads(out: DataOutputStream, reads: List<Read>) {
    out.writeInt(reads.size)
    for (r in reads) {
        out.writeLong(r.timestampNs)
        out.writeUTF(r.text)
        for (c in r.corners) out.writeDouble(c)
        out.writeInt(r.engineId)
        out.writeBoolean(r.symbology != null)
        r.symbology?.let(out::writeUTF)
        out.writeBoolean(r.touchesBorder)
    }
}

/** [encodeSample]'s bytes back; throws on a sample of another version or a cut one */
internal fun decodeSample(data: ByteBuffer): TrackSample {
    val bytes = ByteArray(data.remaining()).also { data.get(it) }
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readUnsignedByte()
        require(version == TRACK_VERSION) { "reads track version $version, not $TRACK_VERSION" }
        val frameNs = input.readLong()
        val entries = List(input.readInt()) {
            when (val kind = input.readUnsignedByte()) {
                ENTRY_ITEMS -> TrackEntry.Items(List(input.readInt()) { input.readUTF() }.toSet())
                ENTRY_READS -> {
                    val ts = input.readLong()
                    val reads = readReads(input)
                    val tracked = readReads(input)
                    val stats = if (input.readBoolean()) {
                        EngineStats(
                            fps = input.readFloat(), prepareMs = input.readFloat(), detectMs = input.readFloat(), decodeMs = input.readFloat(),
                            barcodes = input.readInt(), decoded = input.readInt(), scanMs = input.readFloat(), droppedImages = input.readLong(),
                            refreshAfterMs = input.readLong(),
                            pipe = PipeCounters(input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong()),
                        )
                    } else {
                        null
                    }
                    val meta = if (input.readBoolean()) {
                        CaptureMeta(
                            sensorTimestampNs = input.readLong(), exposureNs = input.readLong(), sensitivity = input.readInt(),
                            rollingShutterSkewNs = input.readLong(), aeCompensation = input.readInt(), fpsRange = input.readUTF(),
                            afState = input.readInt(), focusDiopters = input.readFloat(),
                        )
                    } else {
                        null
                    }
                    TrackEntry.Reads(ArEvent.Reads(ts, reads, stats, tracked), meta)
                }
                else -> throw IllegalArgumentException("reads track entry $kind")
            }
        }
        return TrackSample(frameNs, entries)
    }
}

private fun readReads(input: DataInputStream): List<Read> = List(input.readInt()) {
    val ts = input.readLong()
    val text = input.readUTF()
    val corners = List(8) { input.readDouble() }
    val engineId = input.readInt()
    val symbology = if (input.readBoolean()) input.readUTF() else null
    Read(ts, text, corners, engineId, symbology, input.readBoolean())
}

/**
 * The entries waiting for the GL thread's next fresh frame while a session records: the engine worker and the main
 * thread [offer] them as they reach the app (reads come ~200 ms after their capture), the GL thread [drain]s them into
 * that frame's sample.
 */
internal class TrackQueue {
    private val waiting = ConcurrentLinkedQueue<TrackEntry>()

    fun offer(entry: TrackEntry) {
        waiting.add(entry)
    }

    /** The entries waiting now as one sample of the frame at [frameCameraNs], encoded; null when none waits */
    fun drain(frameCameraNs: Long): ByteArray? {
        if (waiting.isEmpty()) return null
        val entries = ArrayList<TrackEntry>()
        while (true) entries += waiting.poll() ?: break
        return encodeSample(TrackSample(frameCameraNs, entries))
    }
}

/**
 * The sidecar of a recording ([file] with ".txt" in place of ".mp4"): the app stream the reads' corners are in, the
 * item list at the start and the camera's trace line, which a replay needs before its first frame
 */
internal data class RecordingInfo(val stream: AppStream, val items: Set<String>, val cameraLine: String?) {
    fun encode(): String = buildString {
        append("arcount-recording 1\n")
        append("stream ").append(stream.name).append('\n')
        items.forEach { append("item ").append(it).append('\n') }
        cameraLine?.let { append("camera ").append(it).append('\n') }
    }

    companion object {
        fun sidecarOf(recording: File) = File(recording.parentFile, recording.name.removeSuffix(".mp4") + ".txt")

        fun decode(text: String): RecordingInfo {
            val lines = text.lines()
            require(lines.firstOrNull() == "arcount-recording 1") { "not an AR Item Count recording sidecar" }
            var stream: AppStream? = null
            val items = LinkedHashSet<String>()
            var camera: String? = null
            for (line in lines.drop(1)) when {
                line.startsWith("stream ") -> stream = AppStream.valueOf(line.removePrefix("stream "))
                line.startsWith("item ") -> items += line.removePrefix("item ")
                line.startsWith("camera ") -> camera = line.removePrefix("camera ")
            }
            return RecordingInfo(requireNotNull(stream) { "the sidecar names no stream" }, items, camera)
        }
    }
}
