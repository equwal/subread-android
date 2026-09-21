package space.subread.core

import java.io.ByteArrayOutputStream

/**
 * "No change" frames for an H.264 video of a still picture.
 *
 * An encoder is given the picture once and makes one key frame. Each frame
 * after it is written here by hand: a P slice in which each macroblock is
 * skipped. A decoder shows the key frame again, bit for bit, and the frame
 * costs about ten bytes. An encoder asked for the same frame spends kilobytes
 * on it, because its rate control has bits to use.
 *
 * Only streams with CAVLC entropy coding, one slice group and frame
 * macroblocks are handled (Baseline profile is always like that). For another
 * stream, [skipFrames] throws [Unsupported] and the caller must use the
 * encoder's own frames.
 */
object H264Still {
    class Unsupported(message: String) : Exception(message)

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /**
     * The frames that follow a key frame, in order. Frame `i` of the result is
     * frame `i + 1` of the group of pictures. Each is one NAL unit with a
     * four-byte start code, as MediaCodec gives and MediaMuxer takes.
     *
     * @param sps the sequence parameter set, with or without a start code
     * @param pps the picture parameter set, with or without a start code
     */
    fun skipFrames(sps: ByteArray, pps: ByteArray, count: Int): List<ByteArray> {
        try {
            val s = Sps(BitReader(unescape(payload(sps, 7))))
            val p = Pps(BitReader(unescape(payload(pps, 8))))
            return (1..count).map { slice(s, p, it) }
        } catch (e: IndexOutOfBoundsException) {
            throw Unsupported("The parameter sets are cut short.")
        }
    }

    /** level_idc, the largest frame in macroblocks, the most macroblocks in a second (H.264 table A-1). */
    private val LEVELS = listOf(
        Triple(31, 3_600, 108_000), Triple(32, 5_120, 216_000), Triple(40, 8_192, 245_760),
        Triple(42, 8_704, 522_240), Triple(50, 22_080, 589_824), Triple(51, 36_864, 983_040),
        Triple(52, 36_864, 2_073_600), Triple(60, 139_264, 4_177_920), Triple(61, 139_264, 8_355_840),
        Triple(62, 139_264, 16_711_680),
    )

    /** The lowest level that allows this picture size at this frame rate. */
    fun level(width: Int, height: Int, fps: Int): Int {
        val macroblocks = ((width + 15) / 16) * ((height + 15) / 16)
        return LEVELS.firstOrNull { (_, frame, second) -> macroblocks <= frame && macroblocks.toLong() * fps <= second }?.first
            ?: throw Unsupported("no H.264 level allows ${width}x$height at $fps frames a second")
    }

    /**
     * The sequence parameter set with its level raised to [level]. The encoder
     * sees one frame and declares a level for that; the file holds more frames
     * in a second, and a decoder must be told the truth. Never lowers a level.
     */
    fun withLevel(sps: ByteArray, level: Int): ByteArray {
        val at = sps.size - payload(sps, 7).size + 2      // profile_idc, constraint flags, level_idc
        val out = sps.copyOf()
        if (out[at].toInt() and 0xff < level) out[at] = level.toByte()
        return out
    }

    private class Sps(r: BitReader) {
        val log2MaxFrameNum: Int
        val pocType: Int
        var log2MaxPocLsb = 0
        var deltaPocAlwaysZero = false
        val macroblocks: Int

        init {
            val profile = r.bits(8)
            r.bits(16)                      // constraint flags, level
            r.ue()                          // seq_parameter_set_id
            if (profile in HIGH_PROFILES) {
                val chroma = r.ue()
                if (chroma == 3 && r.bit() == 1) throw Unsupported("separate colour planes")
                r.ue(); r.ue(); r.bit()     // bit depths, transform bypass
                if (r.bit() == 1) throw Unsupported("scaling matrix")
            }
            log2MaxFrameNum = r.ue() + 4
            pocType = r.ue()
            when (pocType) {
                0 -> log2MaxPocLsb = r.ue() + 4
                1 -> {
                    deltaPocAlwaysZero = r.bit() == 1
                    r.se(); r.se()
                    repeat(r.ue()) { r.se() }
                }
            }
            r.ue(); r.bit()                 // max_num_ref_frames, gaps allowed
            val width = r.ue() + 1
            val height = r.ue() + 1
            if (r.bit() == 0) throw Unsupported("field macroblocks")
            macroblocks = width * height
        }
    }

    private class Pps(r: BitReader) {
        val id = r.ue()
        val bottomFieldPoc: Boolean
        val deblockingControl: Boolean
        val redundantPicCnt: Boolean

        init {
            r.ue()                          // seq_parameter_set_id
            if (r.bit() == 1) throw Unsupported("CABAC")
            bottomFieldPoc = r.bit() == 1
            if (r.ue() != 0) throw Unsupported("slice groups")
            r.ue(); r.ue()                  // default reference counts
            if (r.bit() == 1) throw Unsupported("weighted prediction")
            r.bits(2)
            r.se(); r.se(); r.se()          // initial QP, QS, chroma QP offset
            deblockingControl = r.bit() == 1
            r.bit()
            redundantPicCnt = r.bit() == 1
        }
    }

    /** Frame [n] after the key frame: slice header, one run of skipped macroblocks, end. */
    private fun slice(s: Sps, p: Pps, n: Int): ByteArray {
        val w = BitWriter()
        w.ue(0)                             // first_mb_in_slice
        w.ue(5)                             // slice_type: P, and so is every slice of the picture
        w.ue(p.id)
        w.bits(n % (1 shl s.log2MaxFrameNum), s.log2MaxFrameNum)
        if (s.pocType == 0) {
            w.bits((2 * n) % (1 shl s.log2MaxPocLsb), s.log2MaxPocLsb)
            if (p.bottomFieldPoc) w.se(0)
        }
        if (s.pocType == 1 && !s.deltaPocAlwaysZero) {
            w.se(0)
            if (p.bottomFieldPoc) w.se(0)
        }
        if (p.redundantPicCnt) w.ue(0)
        w.bit(1); w.ue(0)                   // one reference picture: the frame before
        w.bit(0)                            // no reference list modification
        w.bit(0)                            // sliding-window reference marking
        w.se(0)                             // slice_qp_delta
        if (p.deblockingControl) w.ue(1)    // no deblocking: there is nothing to smooth
        w.ue(s.macroblocks)                 // mb_skip_run: all of them
        val rbsp = w.trailing()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1, 0x41))    // start code; reference picture, non-IDR slice
        out.write(escape(rbsp))
        return out.toByteArray()
    }

    /** The bytes after the NAL header, which must be of [type]. */
    private fun payload(nal: ByteArray, type: Int): ByteArray {
        var at = 0
        while (at < nal.size && nal[at].toInt() == 0) at++
        if (at > 0 && at < nal.size && nal[at].toInt() == 1) at++ else at = 0
        if (at >= nal.size || nal[at].toInt() and 0x1f != type) throw Unsupported("not a NAL unit of type $type")
        return nal.copyOfRange(at + 1, nal.size)
    }

    /** Puts in the 0x03 bytes that keep a start code from showing up inside a NAL unit. */
    internal fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        for (b in rbsp) {
            if (zeros >= 2 && b.toInt() and 0xff <= 3) { out.write(3); zeros = 0 }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal class BitReader(private val data: ByteArray) {
        private var at = 0
        fun bit(): Int {
            if (at shr 3 >= data.size) throw IndexOutOfBoundsException()
            val b = (data[at shr 3].toInt() shr (7 - (at and 7))) and 1
            at++
            return b
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) if (++zeros > 31) throw Unsupported("bad Exp-Golomb code")
            return (1 shl zeros) - 1 + bits(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    internal class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var filled = 0
        fun bit(b: Int) {
            current = (current shl 1) or (b and 1)
            if (++filled == 8) { out.write(current); current = 0; filled = 0 }
        }
        fun bits(v: Int, count: Int) { for (i in count - 1 downTo 0) bit(v shr i) }
        fun ue(v: Int) {
            val x = v + 1
            val length = 32 - Integer.numberOfLeadingZeros(x)
            bits(0, length - 1)
            bits(x, length)
        }
        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)
        /** The stop bit, then zeros to the end of the byte. */
        fun trailing(): ByteArray {
            bit(1)
            while (filled != 0) bit(0)
            return out.toByteArray()
        }
    }
}
