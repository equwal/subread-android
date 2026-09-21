package space.subread.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.app.video.VideoMaker
import space.subread.app.video.VideoSize
import space.subread.core.BookText
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The video, made on a device and then read back with the platform's own
 * demuxer: the tracks a player will find, and how long each is.
 * Uses the local media of OnDeviceJobTest, and skips without it.
 */
@RunWith(AndroidJUnit4::class)
class VideoMakerTest {

    @Test
    fun fromAnMp3() = check(".mp3")      // encoded to AAC

    @Test
    fun fromAnM4b() = check(".m4b")      // AAC, copied

    @Test
    fun fullHdAtSixtyFramesASecond() = check(".m4b", VideoSize(1920, 1080), 60)

    /** On most devices a hardware encoder makes this one. */
    @Test
    fun theLargestSizeOfThisDevice() = check(".m4b", VideoMaker.sizes().last(), 30)

    private fun check(suffix: String, size: VideoSize = VideoMaker.sizes().first(), fps: Int = 1) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names = instrumentation.context.assets.list("local").orEmpty()
        val audioName = names.firstOrNull { it.endsWith(suffix) }
        val bookName = names.firstOrNull { it.endsWith(".epub") }
        assumeTrue("no local test media", audioName != null && bookName != null)

        val app = instrumentation.targetContext
        fun stage(name: String): File = File(app.cacheDir, name).also { out ->
            instrumentation.context.assets.open("local/$name").use { src -> out.outputStream().use { src.copyTo(it) } }
        }
        val audio = stage(audioName!!)
        val cover = stage(bookName!!).inputStream().use { BookText.cover(it) }
        assertTrue("the epub has a cover", cover != null && cover.size > 1024)

        val tag = "video$suffix ${size.label}$fps"
        val out = File(app.cacheDir, "video$suffix.${size.label}$fps.mp4").apply { delete(); createNewFile() }
        val started = System.nanoTime()
        VideoMaker.make(app, Uri.fromFile(audio), cover, Uri.fromFile(out), size, fps) {}
        val seconds = (System.nanoTime() - started) / 1e9

        val ex = MediaExtractor()
        ex.setDataSource(out.absolutePath)
        val tracks = (0 until ex.trackCount).associate {
            val f = ex.getTrackFormat(it)
            f.getString(MediaFormat.KEY_MIME)!! to f.getLong(MediaFormat.KEY_DURATION)
        }
        ex.release()
        Log.i("SubReadTest", "$tag: ${out.length()} B (audio ${audio.length()} B) in %.1fs, tracks=$tracks".format(seconds))

        assertEquals(setOf("video/avc", "audio/mp4a-latm"), tracks.keys)
        val video = tracks.getValue("video/avc")
        val sound = tracks.getValue("audio/mp4a-latm")
        assertTrue("sound is ${sound / 1e6}s", sound in 70_000_000..80_000_000)
        // The picture runs for the length of the sound, to within a frame.
        assertTrue("video $video vs audio $sound", abs(video - sound) <= 1_500_000 / fps + 100_000)

        // Each frame decodes, and the last frame, many "no change" frames after
        // the second key frame, is still the first picture.
        val luma = decodeLuma(out)
        assertEquals("frames", (video * fps / 1_000_000.0).roundToInt(), luma.count)
        assertEquals(size.width to size.height, luma.width to luma.height)
        assertTrue("the last frame is not the first frame", luma.first.contentEquals(luma.last))
        val wanted = VideoMaker.Yuv(VideoMaker.picture(cover, size), size).y
        val psnr = psnr(wanted, luma.last)
        Log.i("SubReadTest", "$tag: picture PSNR %.1f dB".format(psnr))
        assertTrue("the picture is poor: PSNR $psnr dB", psnr > 38)

        // A still picture must cost next to nothing. Only the key frames cost: one each
        // minute, in proportion to the picture size. The frame rate costs 20 bytes a frame.
        val videoBytes = trackBytes(out, "video/avc")
        Log.i("SubReadTest", "$tag: video track $videoBytes B, file ${out.length() - audio.length()} B over the audio")
        val keyFrame = 150_000L * size.width * size.height / (1280 * 720)
        assertTrue("picture costs $videoBytes B", videoBytes < 2 * keyFrame + luma.count * 20)
    }

    private class Luma(val count: Int, val width: Int, val height: Int, val first: ByteArray, val last: ByteArray)

    private fun trackBytes(file: File, mime: String): Long {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        ex.selectTrack((0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime })
        var total = 0L
        while (ex.sampleSize >= 0) { total += ex.sampleSize; ex.advance() }
        ex.release()
        return total
    }

    private fun decodeLuma(file: File): Luma {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        // Needed to read frames as an Image.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType("video/avc")
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var count = 0
        var first: ByteArray? = null
        var last = ByteArray(0)
        var fed = false
        var done = false
        while (!done) {
            if (!fed) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fed = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) {
                throw AssertionError("the decoder failed after $count frames, fed up to ${ex.sampleTime / 1_000_000}s", e)
            }
            if (o < 0) continue
            // Copy the first frame and the frames of the last second only: a copy of each frame takes minutes.
            if (info.size > 0 && first != null && info.presentationTimeUs < duration - 1_000_000) count++
            else if (info.size > 0) {
                val plane = codec.getOutputImage(o)!!.planes[0]
                val y = ByteArray(width * height)
                for (row in 0 until height) {
                    plane.buffer.position(row * plane.rowStride)
                    plane.buffer.get(y, row * width, width)
                }
                if (first == null) first = y
                last = y
                count++
            }
            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(o, false)
        }
        codec.stop(); codec.release(); ex.release()
        return Luma(count, width, height, first!!, last)
    }

    private fun psnr(a: ByteArray, b: ByteArray): Double {
        var sum = 0.0
        for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); sum += d * d }
        return 10 * log10(255.0 * 255.0 / (sum / a.size).coerceAtLeast(1e-9))
    }
}
