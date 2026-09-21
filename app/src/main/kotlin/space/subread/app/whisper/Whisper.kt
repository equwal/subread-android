package space.subread.app.whisper

import android.content.Context
import space.subread.core.TranscriptSegment
import java.io.File

/** JNI surface. See src/main/cpp/whisper_jni.c. */
internal object WhisperLib {
    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun transcribe(ptr: Long, samples: FloatArray, threads: Int, language: String): Int
    external fun cancel()
    external fun progress(): Int
    external fun segmentCount(ptr: Long): Int
    external fun segmentText(ptr: Long, index: Int): ByteArray
    external fun segmentStart(ptr: Long, index: Int): Long
    external fun segmentEnd(ptr: Long, index: Int): Long
    external fun detectedLanguage(ptr: Long): String
    external fun systemInfo(): String
}

class WhisperUnavailable(message: String) : Exception(message)
class TranscriptionCancelled : Exception("cancelled")

/** A loaded model. Not thread-safe: one transcription at a time. */
class Whisper private constructor(private var ptr: Long) : AutoCloseable {

    /** The language whisper settled on in the last call ("ja", "ru", ...). */
    val detectedLanguage: String get() = WhisperLib.detectedLanguage(ptr)

    /**
     * @param samples 16 kHz mono, -1..1
     * @param offset where this buffer starts in the whole recording, seconds
     */
    fun transcribe(samples: FloatArray, language: String, offset: Double): List<TranscriptSegment> {
        when (val rc = WhisperLib.transcribe(ptr, samples, threads, language)) {
            0 -> {}
            1000 -> throw TranscriptionCancelled()
            else -> throw WhisperUnavailable("The speech model failed on this audio (code $rc).")
        }
        return (0 until WhisperLib.segmentCount(ptr)).mapNotNull { i ->
            // Lenient decode: the tiny model can end a segment mid-character.
            val text = String(WhisperLib.segmentText(ptr, i), Charsets.UTF_8).replace("�", "").trim()
            if (text.isEmpty()) null
            else TranscriptSegment(
                text,
                offset + WhisperLib.segmentStart(ptr, i) / 100.0,
                offset + WhisperLib.segmentEnd(ptr, i) / 100.0,
            )
        }
    }

    override fun close() {
        if (ptr != 0L) WhisperLib.freeContext(ptr)
        ptr = 0
    }

    companion object {
        private const val MODEL_ASSET = "models/ggml-tiny-q8_0.bin"

        /** 0..100 within the buffer currently being transcribed. */
        val progress: Int get() = if (loaded) WhisperLib.progress() else 0

        fun cancel() { if (loaded) WhisperLib.cancel() }

        @Volatile private var loaded = false

        val threads: Int by lazy { fastCores() }

        fun open(context: Context): Whisper {
            requireCpu()
            if (!loaded) {
                System.loadLibrary("subread_whisper")
                loaded = true
            }
            val model = File(context.filesDir, "ggml-tiny-q8_0.bin")
            val asset = context.assets.openFd(MODEL_ASSET)
            // whisper.cpp wants a path, and an asset inside the APK does not have one.
            if (!model.exists() || model.length() != asset.length) {
                asset.createInputStream().use { src -> model.outputStream().use { src.copyTo(it) } }
            }
            asset.close()
            val ptr = WhisperLib.initContext(model.absolutePath)
            if (ptr == 0L) throw WhisperUnavailable("The speech model could not be loaded.")
            return Whisper(ptr)
        }

        /**
         * The native library is built for ARMv8.2 with half-precision and
         * dot-product instructions. Loading it without them is a crash with no
         * message, so look first.
         */
        private fun requireCpu() {
            val features = runCatching {
                File("/proc/cpuinfo").readLines()
                    .firstOrNull { it.startsWith("Features") }.orEmpty()
                    .substringAfter(':').trim().split(' ').toSet()
            }.getOrDefault(emptySet())
            if (features.isEmpty()) return   // cannot tell; let it try
            val missing = listOf("asimdhp", "asimddp").filter { it !in features }
            if (missing.isNotEmpty()) {
                throw WhisperUnavailable(
                    "This phone's processor is too old for on-device transcription " +
                        "(needs ARMv8.2 half-precision and dot-product support)."
                )
            }
        }

        /**
         * Threads to use: the fast cores only. whisper waits for its slowest
         * thread at every layer, so adding little cores makes it slower, not
         * faster. "Fast" is anything clocked above the slowest cluster.
         */
        private fun fastCores(): Int {
            val max = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull()
            }
            if (max.isEmpty()) return 4
            val slowest = max.min()
            val fast = max.count { it > slowest }
            // All cores alike (or unreadable clusters): use half, at most four.
            return if (fast == 0) (max.size / 2).coerceIn(2, 4) else fast.coerceIn(2, 6)
        }
    }
}
