package space.subread.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class H264StillTest {

    @Test
    fun expGolombCodesRoundTrip() {
        val random = Random(1)
        repeat(2000) {
            val unsigned = List(20) { if (it % 2 == 0) random.nextInt(0, 40) else random.nextInt(0, 1 shl 20) }
            val signed = unsigned.map { if (random.nextBoolean()) it else -it }
            val w = H264Still.BitWriter()
            unsigned.forEach(w::ue)
            signed.forEach(w::se)
            val r = H264Still.BitReader(w.trailing())
            assertEquals(unsigned, List(unsigned.size) { r.ue() })
            assertEquals(signed, List(signed.size) { r.se() })
        }
    }

    @Test
    fun escapedBytesRoundTripAndHoldNoStartCode() {
        val random = Random(2)
        repeat(5000) {
            // Mostly small values: runs of zeros are what the escape is for.
            val rbsp = ByteArray(random.nextInt(0, 40)) { if (random.nextInt(4) == 0) random.nextInt(256).toByte() else random.nextInt(4).toByte() }
            val nal = H264Still.escape(rbsp)
            assertArrayEquals(rbsp, H264Still.unescape(nal))
            for (i in 0..nal.size - 3) {
                assertFalse("start code in ${nal.toList()}", nal[i].toInt() == 0 && nal[i + 1].toInt() == 0 && nal[i + 2].toInt() in 0..2)
            }
        }
    }

    /** A real decoder is the judge: each hand-made frame must decode to the key frame's exact picture. */
    @Test
    fun aDecoderShowsTheKeyFrameAgainForEachSkipFrame() {
        val dir = Files.createTempDirectory("h264still").toFile()
        try {
            val key = encodeKeyFrame(dir, "baseline")
            val nals = split(key)
            val sps = nals.first { it[0].toInt() and 0x1f == 7 }
            val pps = nals.first { it[0].toInt() and 0x1f == 8 }

            // 70 frames: more than frame_num and the picture order count can hold, so both wrap.
            val frames = H264Still.skipFrames(sps, pps, 70)
            assertTrue("a skip frame is ${frames.maxOf { it.size }} bytes", frames.all { it.size <= 16 })
            val stream = File(dir, "still.h264")
            stream.writeBytes(key + frames.reduce { a, b -> a + b })

            val (code, out, err) = run(dir, "ffmpeg", "-v", "error", "-xerror", "-i", stream.name, "-f", "framemd5", "-")
            assertEquals(err, 0, code)
            assertEquals("decoder messages", "", err.trim())
            val hashes = out.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfterLast(',').trim() }
            assertEquals(71, hashes.size)
            assertEquals("each frame is the same picture", 1, hashes.toSet().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aStreamWithCabacIsRefused() {
        val dir = Files.createTempDirectory("h264still").toFile()
        try {
            val nals = split(encodeKeyFrame(dir, "main"))
            val sps = nals.first { it[0].toInt() and 0x1f == 7 }
            val pps = nals.first { it[0].toInt() and 0x1f == 8 }
            assertThrows(H264Still.Unsupported::class.java) { H264Still.skipFrames(sps, pps, 1) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theLevelFollowsThePictureSizeAndTheFrameRate() {
        assertEquals(31, H264Still.level(1280, 720, 1))
        assertEquals(31, H264Still.level(1280, 720, 30))
        assertEquals(32, H264Still.level(1280, 720, 60))
        assertEquals(40, H264Still.level(1920, 1080, 30))
        assertEquals(42, H264Still.level(1920, 1080, 60))
        assertEquals(50, H264Still.level(2560, 1440, 30))
        assertEquals(51, H264Still.level(2560, 1440, 60))
        assertEquals(52, H264Still.level(3840, 2160, 60))
        assertEquals(61, H264Still.level(7680, 4320, 60))
        assertThrows(H264Still.Unsupported::class.java) { H264Still.level(16384, 8640, 60) }
    }

    @Test
    fun theLevelOfAParameterSetIsRaisedAndNeverLowered() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 31, 0x55)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 42, 0x55), H264Still.withLevel(sps, 42))
        assertArrayEquals(sps, H264Still.withLevel(sps, 30))
        assertArrayEquals(byteArrayOf(0x67, 0x42, 0, 51), H264Still.withLevel(byteArrayOf(0x67, 0x42, 0, 40), 51))
    }

    @Test
    fun cutParameterSetsAreRefused() {
        assertThrows(H264Still.Unsupported::class.java) { H264Still.skipFrames(byteArrayOf(0x67, 0x42), byteArrayOf(0x68), 1) }
        assertThrows(H264Still.Unsupported::class.java) { H264Still.skipFrames(byteArrayOf(0x68, 0x42), byteArrayOf(0x68), 1) }
    }

    /** One key frame of a test card. Skips the test on a machine with no ffmpeg that can encode H.264. */
    private fun encodeKeyFrame(dir: File, profile: String): ByteArray {
        val cabac = if (profile == "main") arrayOf("-coder", "cabac") else emptyArray()
        val made = listOf("libx264" to profile, "libopenh264" to profile.replace("baseline", "constrained_baseline")).any { (encoder, name) ->
            runCatching {
                run(dir, "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=1", "-frames:v", "1",
                    "-c:v", encoder, "-profile:v", name, *cabac, "-pix_fmt", "yuv420p", "-f", "h264", "key.h264").first == 0
            }.getOrDefault(false)
        }
        assumeTrue("no ffmpeg with an H.264 encoder on the PATH", made)
        return File(dir, "key.h264").readBytes()
    }

    private fun run(dir: File, vararg command: String): Triple<Int, String, String> {
        val out = File(dir, "stdout.txt")
        val err = File(dir, "stderr.txt")
        val process = ProcessBuilder(*command).directory(dir).redirectOutput(out).redirectError(err).start()
        return Triple(process.waitFor(), out.readText(), err.readText())
    }

    /** NAL units of an Annex B stream, without their start codes. */
    private fun split(stream: ByteArray): List<ByteArray> {
        val starts = (0..stream.size - 3).filter { stream[it].toInt() == 0 && stream[it + 1].toInt() == 0 && stream[it + 2].toInt() == 1 }
        return starts.mapIndexed { k, at ->
            var end = if (k + 1 < starts.size) starts[k + 1] else stream.size
            while (end > at + 3 && stream[end - 1].toInt() == 0) end--
            stream.copyOfRange(at + 3, end)
        }
    }
}
