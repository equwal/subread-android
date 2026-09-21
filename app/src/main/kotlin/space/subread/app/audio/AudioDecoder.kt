package space.subread.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Streams any audio file the platform can play as 16 kHz mono float chunks,
 * which is the only thing the speech model accepts.
 *
 * A ten-hour book is over two gigabytes as PCM, so it is never held whole:
 * each call to [next] decodes a couple of minutes, cuts at the quietest moment
 * near the end (so no word is split across two transcriptions) and carries the
 * remainder into the next chunk.
 */
class AudioDecoder(context: Context, uri: Uri, startAtSeconds: Double = 0.0) : Closeable {

    class Chunk(val samples: FloatArray, val startSeconds: Double) {
        val endSeconds: Double get() = startSeconds + samples.size / TARGET_RATE.toDouble()
    }

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    val durationSeconds: Double
    private var sourceRate: Int
    private var channels: Int

    private var inputDone = false
    private var outputDone = false

    /** Decoded, mono, still at the source rate; what did not fit the last chunk. */
    private var pending = FloatBuffer()
    /** Time of pending[0], in seconds. Taken from the decoder, so seeking stays honest. */
    private var pendingStart = -1.0
    /**
     * A seek lands on the frame *before* the one asked for. Audio ahead of this
     * point was transcribed in an earlier session and is thrown away, or the
     * resumed chunk would overlap the last one and repeat its final words.
     */
    private val skipUntil = startAtSeconds

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw IllegalArgumentException("No audio track in this file.")
        extractor.selectTrack(track)

        val format = extractor.getTrackFormat(track)
        durationSeconds = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        if (startAtSeconds > 0) {
            extractor.seekTo((startAtSeconds * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** The next chunk, or null at the end of the file. */
    fun next(targetSeconds: Int = CHUNK_SECONDS): Chunk? {
        // Re-read the rate each time round: HE-AAC files announce half of what
        // the decoder actually produces, and only say so once decoding starts.
        while (pending.size < targetSeconds.toLong() * sourceRate && !outputDone) pump()
        if (pending.size == 0) return null
        val want = targetSeconds.toLong() * sourceRate

        val cut = if (outputDone && pending.size <= want) pending.size
        else quietestPoint(pending, sourceRate, min(want, pending.size.toLong()).toInt())

        val head = pending.take(cut)
        val start = pendingStart
        pending = pending.drop(cut)
        pendingStart = start + cut / sourceRate.toDouble()
        return Chunk(resample(head, sourceRate), start)
    }

    private fun pump() {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buffer = codec.getInputBuffer(i)!!
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }

        when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // The container can be wrong about these; the decoder is not.
                val f = codec.outputFormat
                sourceRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            in 0..Int.MAX_VALUE -> {
                if (info.size > 0) {
                    val bufferStart = info.presentationTimeUs / 1e6
                    val shorts = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val skip = if (bufferStart >= skipUntil) 0
                    else min(frames, ((skipUntil - bufferStart) * sourceRate).toInt())
                    shorts.position(shorts.position() + skip * channels)
                    if (frames > skip && pendingStart < 0) {
                        pendingStart = bufferStart + skip / sourceRate.toDouble()
                    }
                    pending.ensure(frames - skip)
                    for (f in skip until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += shorts.get()
                        pending.add(sum / (32768f * channels))
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        const val TARGET_RATE = 16_000
        const val CHUNK_SECONDS = 120

        /** How far back from the end of a chunk to look for a pause. */
        private const val SEARCH_SECONDS = 12
        private const val WINDOW_SECONDS = 0.25

        /** Index at which to cut: the middle of the quietest quarter second near [limit]. */
        internal fun quietestPoint(buf: FloatBuffer, rate: Int, limit: Int): Int {
            val window = (WINDOW_SECONDS * rate).toInt()
            val from = max(0, limit - SEARCH_SECONDS * rate)
            if (limit - from <= window * 2) return limit

            var energy = 0.0
            for (i in from until from + window) energy += abs(buf[i])
            var best = energy
            var bestAt = from
            var i = from
            while (i + window < limit) {
                energy += abs(buf[i + window]) - abs(buf[i])
                i++
                if (energy < best) { best = energy; bestAt = i }
            }
            return bestAt + window / 2
        }

        /**
         * To 16 kHz with a triangular kernel as wide as the rate ratio: a
         * low-pass and an interpolator in one pass. Plenty for speech
         * recognition, and it costs a handful of multiplies per sample.
         */
        internal fun resample(src: FloatArray, rate: Int): FloatArray {
            if (rate == TARGET_RATE) return src
            val step = rate / TARGET_RATE.toDouble()
            val half = max(step, 1.0)
            val out = FloatArray((src.size / step).toInt())
            for (o in out.indices) {
                val centre = o * step
                val lo = max(0, (centre - half).toInt() + 1)
                val hi = min(src.size - 1, (centre + half).toInt())
                var sum = 0.0
                var weight = 0.0
                for (k in lo..hi) {
                    val w = 1.0 - abs(k - centre) / half
                    if (w > 0) { sum += w * src[k]; weight += w }
                }
                out[o] = if (weight > 0) (sum / weight).toFloat() else 0f
            }
            return out
        }
    }
}

/** A growable float array. Minutes of audio as boxed Floats would be absurd. */
internal class FloatBuffer(capacity: Int = 1 shl 16) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(max(data.size * 2, size + extra))
    }

    fun add(v: Float) { data[size++] = v }

    fun take(n: Int): FloatArray = data.copyOf(n)

    fun drop(n: Int): FloatBuffer = FloatBuffer(max(size - n, 1 shl 16)).also {
        System.arraycopy(data, n, it.data, 0, size - n)
        it.size = size - n
    }
}
