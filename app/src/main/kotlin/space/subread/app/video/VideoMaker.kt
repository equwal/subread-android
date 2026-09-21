package space.subread.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import space.subread.core.H264Still
import java.nio.ByteBuffer

/**
 * A video of the book: a still picture over the audiobook's own audio, as an
 * MP4 that any player opens and YouTube accepts. The subtitles go beside it as
 * an .srt with the same name, which players load by themselves.
 *
 * A still picture is cheap if nothing is encoded twice. The encoder makes one
 * key frame. The frames after it say "no change" in about ten bytes each; they
 * are written by hand (see H264Still), because an encoder spends kilobytes on
 * the same frame. That minute is written again and again with new time stamps.
 * So the frame rate costs almost nothing, and the picture size costs only in
 * the one key frame of each minute.
 * AAC audio is copied as it is; other audio is encoded to AAC once, which the
 * MP4 container needs.
 */
class VideoSize(val width: Int, val height: Int) {
    val label: String get() = "${height}p"
}

object VideoMaker {
    private const val TAG = "SubRead"

    /** Sizes to offer. A device makes only those for which it has an encoder: see [sizes]. */
    private val SIZES = listOf(VideoSize(1280, 720), VideoSize(1920, 1080), VideoSize(2560, 1440),
        VideoSize(3840, 2160), VideoSize(7680, 4320))
    val FRAME_RATES = listOf(1, 24, 30, 60)
    private const val GOP_SECONDS = 60
    private const val KEY_FRAME_QP = 26

    private class Frame(val bytes: ByteArray, val key: Boolean)
    private class Sample(val bytes: ByteArray, val ptsUs: Long)

    /** The sizes this device can make, smallest first. The first one is the default. */
    fun sizes(): List<VideoSize> = SIZES.filter { encoderFor(it) != null }.ifEmpty { SIZES.take(1) }

    /**
     * @param cover image bytes, or null for a plain card
     * @param size one of [sizes]
     * @param fps frames in a second, 1 to 60
     */
    fun make(
        context: Context, audio: Uri, cover: ByteArray?, dest: Uri,
        size: VideoSize = SIZES[0], fps: Int = 1, onProgress: (Float) -> Unit,
    ) {
        require(fps in 1..60) { "fps $fps" }
        val yuv = Yuv(picture(cover, size), size)
        var rate = fps
        var (videoFormat, gop) = encodeStill(yuv, size, 1)
        try {
            val skips = H264Still.skipFrames(bytes(videoFormat, "csd-0"), bytes(videoFormat, "csd-1"), GOP_SECONDS * fps - 1)
            gop = gop + skips.map { Frame(it, false) }
        } catch (e: H264Still.Unsupported) {
            // Bigger and at one frame a second, but correct: the encoder's own frames.
            Log.w(TAG, "still: no hand-made frames for this stream (${e.message})")
            rate = 1
            encodeStill(yuv, size, GOP_SECONDS).let { videoFormat = it.first; gop = it.second }
        }
        val level = H264Still.level(size.width, size.height, rate)
        videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(H264Still.withLevel(bytes(videoFormat, "csd-0"), level)))
        Log.i(TAG, "still: ${size.width}x${size.height} at $rate fps, level $level, ${gop.size} frames, " +
            "key ${gop[0].bytes.size} B, the rest ${gop.drop(1).sumOf { it.bytes.size }} B")

        val extractor = MediaExtractor()
        extractor.setDataSource(context, audio, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw IllegalArgumentException("No audio track in this file.")
        extractor.selectTrack(track)
        val source = extractor.getTrackFormat(track)
        val durationUs = if (source.containsKey(MediaFormat.KEY_DURATION)) source.getLong(MediaFormat.KEY_DURATION) else 0L

        val sound: AudioSource =
            if (source.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) Copy(extractor, source)
            else Transcode(extractor, source)

        context.contentResolver.openFileDescriptor(dest, "rw")!!.use { pfd ->
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val v = muxer.addTrack(videoFormat)
                val a = muxer.addTrack(sound.format())
                muxer.start()

                val info = MediaCodec.BufferInfo()
                var frame = 0L
                var frameUs = 0L
                fun picture() {
                    val f = gop[(frame % gop.size).toInt()]
                    info.set(0, f.bytes.size, frameUs, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(v, ByteBuffer.wrap(f.bytes), info)
                    frame++
                    frameUs = frame * 1_000_000 / rate
                }

                var lastUs = 0L
                while (true) {
                    val s = sound.next() ?: break
                    // Keep the two tracks side by side in the file, so a player
                    // does not have to jump about to find the next of either.
                    while (frameUs <= s.ptsUs) picture()
                    info.set(0, s.bytes.size, s.ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(a, ByteBuffer.wrap(s.bytes), info)
                    lastUs = s.ptsUs
                    if (durationUs > 0) onProgress((s.ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                while (frameUs <= lastUs) picture()
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
                sound.close()
                extractor.release()
            }
        }
    }

    private val AVC_LEVELS = mapOf(
        31 to MediaCodecInfo.CodecProfileLevel.AVCLevel31, 32 to MediaCodecInfo.CodecProfileLevel.AVCLevel32,
        40 to MediaCodecInfo.CodecProfileLevel.AVCLevel4, 42 to MediaCodecInfo.CodecProfileLevel.AVCLevel42,
        50 to MediaCodecInfo.CodecProfileLevel.AVCLevel5, 51 to MediaCodecInfo.CodecProfileLevel.AVCLevel51,
        52 to MediaCodecInfo.CodecProfileLevel.AVCLevel52,
        // AVCLevel6, AVCLevel61 and AVCLevel62. The names are in API 29 and later; the values are the same on each version.
        60 to 0x20000, 61 to 0x40000, 62 to 0x80000,
    )

    private fun bytes(format: MediaFormat, key: String): ByteArray {
        val buffer = format.getByteBuffer(key) ?: throw H264Still.Unsupported("no $key")
        return ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }

    // ------------------------------------------------------------------ audio

    private interface AudioSource {
        fun format(): MediaFormat
        fun next(): Sample?
        fun close() {}
    }

    /** AAC goes into the MP4 untouched. */
    private class Copy(val extractor: MediaExtractor, val source: MediaFormat) : AudioSource {
        private val buffer = ByteBuffer.allocate(1 shl 18)
        override fun format() = source
        override fun next(): Sample? {
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) return null
            val bytes = ByteArray(n)
            buffer.position(0)
            buffer.get(bytes, 0, n)
            val s = Sample(bytes, extractor.sampleTime)
            extractor.advance()
            return s
        }
    }

    /**
     * MP3, Opus and the rest: decoded and encoded to AAC, once.
     *
     * Each call into a codec costs about a millisecond on a slow device, and
     * that cost, not the arithmetic, sets the speed. So the decoder has its own
     * thread and works while the encoder works, and the encoder gets the sound
     * in large pieces: one call for many frames.
     */
    private class Transcode(val extractor: MediaExtractor, source: MediaFormat) : AudioSource {
        private val decoder = MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(source, null, null, 0); start()
        }
        @Volatile private var rate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        @Volatile private var channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        @Volatile private var failure: Throwable? = null
        @Volatile private var closed = false
        private val pieces = java.util.concurrent.ArrayBlockingQueue<ByteArray>(8)
        private val decoding = kotlin.concurrent.thread(name = "subread-audio-decode") {
            try { decode() } catch (e: Throwable) { if (!closed) failure = e }
            runCatching { pieces.put(END) }
        }

        private var encoder: MediaCodec? = null
        private var outFormat: MediaFormat? = null
        private val info = MediaCodec.BufferInfo()
        private var piece: ByteArray? = null
        private var pieceAt = 0
        private var pcmEnded = false
        private var encoderEnded = false
        private var framesIn = 0L
        private val ready = ArrayDeque<Sample>()
        private var finished = false

        override fun format(): MediaFormat {
            while (outFormat == null && !finished) pump()
            return outFormat ?: throw IllegalStateException("The audio could not be encoded.")
        }

        override fun next(): Sample? {
            while (ready.isEmpty() && !finished) pump()
            return ready.removeFirstOrNull()
        }

        /** The decoder's thread: compressed audio in, PCM out in pieces of about [PIECE] bytes. */
        private fun decode() {
            val info = MediaCodec.BufferInfo()
            val pcm = java.io.ByteArrayOutputStream(PIECE + (1 shl 14))
            var extractorDone = false
            while (!closed) {
                // Each free input buffer gets a frame, so the decoder never waits for this loop.
                while (!extractorDone) {
                    val i = decoder.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, n, 0, 0)
                        extractor.advance()
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The container can be wrong about these; the decoder is not.
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (o < 0) continue
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    decoder.getOutputBuffer(o)!!.apply { position(info.offset); get(bytes) }
                    pcm.write(bytes)
                }
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(o, false)
                if (pcm.size() >= PIECE || (end && pcm.size() > 0)) {
                    pieces.put(pcm.toByteArray())
                    pcm.reset()
                }
                if (end) return
            }
        }

        private fun startEncoder(): MediaCodec {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 80_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PIECE)
            }
            return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }.also { encoder = it }
        }

        /** One step of the encoder's side: take encoded audio out, or put PCM in. */
        private fun pump() {
            encoder?.let { enc ->
                // Wait only when there is nothing to put in.
                val o = enc.dequeueOutputBuffer(info, if (piece != null || pieces.isNotEmpty()) 0 else 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                else if (o >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        enc.getOutputBuffer(o)!!.apply { position(info.offset); get(bytes) }
                        ready += Sample(bytes, info.presentationTimeUs)
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(o, false)
                    if (end) finished = true
                    return
                }
            }

            if (piece == null && !pcmEnded) {
                val next = pieces.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return
                if (next === END) {
                    pcmEnded = true
                    failure?.let { throw it }
                } else {
                    piece = next
                    pieceAt = 0
                }
            }
            if (encoderEnded) return

            val enc = encoder ?: startEncoder()
            val i = enc.dequeueInputBuffer(10_000)
            if (i < 0) return
            val ptsUs = framesIn * 1_000_000 / rate
            val pcm = piece
            if (pcm != null) {
                val buf = enc.getInputBuffer(i)!!
                val frame = 2 * channels
                val n = minOf(buf.capacity() / frame * frame, pcm.size - pieceAt)
                buf.clear()
                buf.put(pcm, pieceAt, n)
                enc.queueInputBuffer(i, 0, n, ptsUs, 0)
                pieceAt += n
                framesIn += n / frame
                if (pieceAt >= pcm.size) piece = null
            } else {
                enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                encoderEnded = true
            }
        }

        override fun close() {
            closed = true
            pieces.clear()              // so a decoder that waits to put a piece can go on, and stop
            decoding.join(2_000)
            runCatching { decoder.stop() }; decoder.release()
            encoder?.let { runCatching { it.stop() }; it.release() }
        }

        private companion object {
            /** About a third of a second of stereo sound. */
            const val PIECE = 1 shl 16
            val END = ByteArray(0)
        }
    }

    // ---------------------------------------------------------------- picture

    /** The cover, letterboxed on a black card; a plain dark card when there is none. */
    internal fun picture(cover: ByteArray?, size: VideoSize): Bitmap {
        val width = size.width
        val height = size.height
        val card = createBitmap(width, height)
        val canvas = android.graphics.Canvas(card)
        canvas.drawColor(Color.rgb(0x16, 0x16, 0x1a))
        val image = cover?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (image != null) {
            canvas.drawColor(Color.BLACK)
            val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
            val w = image.width * scale
            val h = image.height * scale
            canvas.drawBitmap(image, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return card
    }

    /**
     * The encoder for a picture of this size. The software encoder is first:
     * it is the same on each device. A hardware encoder makes the larger sizes.
     */
    private fun encoderFor(size: VideoSize): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { c -> c.isEncoder && c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter { c ->
                runCatching {
                    c.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(size.width, size.height)
                }.getOrDefault(false)
            }
            .sortedBy { c -> if (c.name.startsWith("c2.android.") || c.name.startsWith("OMX.google.")) 0 else 1 }
            .firstOrNull()?.name

    /**
     * The first [count] seconds of the picture at one frame a second. The
     * frames go in as YUV buffers: that is the input each encoder accepts
     * (the MediaTek encoder fails on frames drawn with a Canvas).
     */
    private fun encodeStill(yuv: Yuv, size: VideoSize, count: Int): Pair<MediaFormat, List<Frame>> {
        val width = size.width
        val height = size.height
        val name = encoderFor(size) ?: throw IllegalStateException("This device has no H.264 encoder for ${size.label} video.")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger("video-qp-i-min", KEY_FRAME_QP)
            setInteger("video-qp-i-max", KEY_FRAME_QP)
            // Far longer than the clip: only the first frame is a key frame.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3600)
            // Baseline: each player decodes it, and H264Still can write frames for it.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // The level for one frame a second. make() writes the true level afterwards.
            setInteger(MediaFormat.KEY_LEVEL, AVC_LEVELS.getValue(H264Still.level(width, height, 1)))
        }
        val codec = MediaCodec.createByCodecName(name)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val frames = ArrayList<Frame>()
        var outFormat: MediaFormat? = null
        var queued = 0
        var ended = false
        try {
            while (!ended) {
                if (queued <= count) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (queued == count) {
                            codec.queueInputBuffer(i, 0, 0, queued * 1_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            yuv.fill(codec.getInputImage(i)!!)
                            codec.queueInputBuffer(i, 0, width * height * 3 / 2, queued * 1_000_000L, 0)
                        }
                        queued++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
                if (o < 0) continue
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(info.size)
                    codec.getOutputBuffer(o)!!.apply { position(info.offset); get(bytes) }
                    frames += Frame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(o, false)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        check(frames.size == count && frames[0].key) { "The video encoder made ${frames.size} of $count frames." }
        check(frames.drop(1).none { it.key }) { "The video encoder made more than one key frame." }
        return (outFormat ?: throw IllegalStateException("The video encoder gave no format.")) to frames
    }

    /** The picture as Y, U and V planes (BT.601, video range), made once and copied into each frame. */
    internal class Yuv(picture: Bitmap, size: VideoSize) {
        private val width = size.width
        private val height = size.height
        val y = ByteArray(width * height)
        private val u = ByteArray(width * height / 4)
        private val v = ByteArray(width * height / 4)

        init {
            val argb = IntArray(width * height)
            picture.getPixels(argb, 0, width, 0, 0, width, height)
            for (row in 0 until height) for (col in 0 until width) {
                val c = argb[row * width + col]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                y[row * width + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val k = (row / 2) * (width / 2) + col / 2
                    u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }

        /** Planar or semi-planar, padded or not: the Image says, and this follows. */
        fun fill(image: android.media.Image) {
            copy(y, width, height, image.planes[0])
            copy(u, width / 2, height / 2, image.planes[1])
            copy(v, width / 2, height / 2, image.planes[2])
        }

        private fun copy(src: ByteArray, w: Int, h: Int, plane: android.media.Image.Plane) {
            val buffer = plane.buffer
            if (plane.pixelStride == 1 && plane.rowStride == w) {
                buffer.position(0)
                buffer.put(src, 0, w * h)
                return
            }
            for (row in 0 until h) for (col in 0 until w) {
                buffer.put(row * plane.rowStride + col * plane.pixelStride, src[row * w + col])
            }
        }
    }
}
