package app.lecturenotes

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 区間の音声を書くもの（AAC か、だめなら WAV） */
interface ChunkWriter {
    val file: File
    fun write(pcm: ShortArray, n: Int)
    fun close()
}

/** 16kHz モノラルの AAC（m4a）。32kbps で 1 時間およそ 14MB（WAV の約 1/8） */
class AacWriter(override val file: File, private val bitrate: Int = 32_000) : ChunkWriter {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    private fun ptsUs() = samplesIn * 1_000_000L / SAMPLE_RATE

    override fun write(pcm: ShortArray, n: Int) {
        var off = 0
        var spins = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(false)
                if (++spins > 200) return // 詰まったら諦める（録音を止めない）
                continue
            }
            val buf = codec.getInputBuffer(idx) ?: continue
            buf.clear()
            val count = minOf(n - off, buf.capacity() / 2)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, count)
            codec.queueInputBuffer(idx, 0, count * 2, ptsUs(), 0)
            samplesIn += count
            off += count
            drain(false)
        }
    }

    private fun drain(eos: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos || ++tries > 100) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (out != null && info.size > 0 && started) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        try {
            var idx = -1
            for (i in 0 until 50) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) break
                drain(false)
            }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!started) file.delete()
        }
    }
}

class WavChunkWriter(override val file: File) : ChunkWriter {
    private val w = Wav.Writer(file)
    override fun write(pcm: ShortArray, n: Int) = w.write(pcm, n)
    override fun close() = w.close()
}

object Audio {
    /** 新しい区間のファイルを用意する。AAC が使えなければ WAV */
    fun newChunk(dir: File, index: Int): ChunkWriter = try {
        AacWriter(File(dir, "chunk_%05d.m4a".format(index)))
    } catch (e: Exception) {
        WavChunkWriter(File(dir, "chunk_%05d.wav".format(index)))
    }

    fun mime(f: File) = if (f.name.endsWith(".m4a")) "audio/mp4" else "audio/wav"

    /**
     * m4a（AAC）の中身を、そのまま ADTS 形式（.aac）に並べ直す（音は作り直さない）。
     * Gemini は m4a を受け付けないが、ADTS の AAC は受け付ける。
     */
    fun toAdts(f: File): ByteArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            require(fmt.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) { "AAC ではありません" }
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val freqIdx = rates.indexOf(rate)
            require(freqIdx >= 0) { "対応していない周波数" }
            val profile = 2 // AAC LC
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val len = size + 7
                out.write(0xFF)
                out.write(0xF1)
                out.write(((profile - 1) shl 6) or (freqIdx shl 2) or (ch shr 2))
                out.write(((ch and 3) shl 6) or (len shr 11))
                out.write((len shr 3) and 0xFF)
                out.write(((len and 7) shl 5) or 0x1F)
                out.write(0xFC)
                val arr = ByteArray(size)
                buf.get(arr, 0, size)
                out.write(arr)
                ex.advance()
            }
            val bytes = out.toByteArray()
            require(bytes.size > 100) { "音声が空です" }
            return bytes
        } finally {
            ex.release()
        }
    }

    /** m4a を WAV のバイト列に戻す（Gemini に送るため） */
    fun toWavBytes(f: File): ByteArray {
        if (f.name.endsWith(".wav")) return f.readBytes()
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        ex.selectTrack(0)
        val inFmt = ex.getTrackFormat(0)
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        try {
            var guard = 0
            while (guard++ < 1_000_000) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!
                    val arr = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(arr)
                    pcm.write(arr)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val data = pcm.toByteArray()
        return Wav.header(data.size.toLong(), rate, channels) + data
    }

    /** WAV を m4a に変える（古い記録を小さくする・書き出しのため） */
    fun wavToM4a(wav: File, out: File) {
        val w = AacWriter(out)
        try {
            wav.inputStream().buffered().use { i ->
                i.skip(44)
                val bytes = ByteArray(SAMPLE_RATE / 10 * 2)
                val shorts = ShortArray(SAMPLE_RATE / 10)
                while (true) {
                    val n = i.read(bytes)
                    if (n <= 0) break
                    val count = n / 2
                    ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, count)
                    w.write(shorts, count)
                }
            }
        } finally {
            w.close()
        }
    }

    /** m4a の区間をつないで1つの m4a にする（音は作り直さない） */
    fun concatM4a(files: List<File>, out: File, onProgress: (Float) -> Unit = {}) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offsetUs = 0L
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            files.forEachIndexed { n, f ->
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.path)
                    ex.selectTrack(0)
                    if (track < 0) {
                        track = muxer.addTrack(ex.getTrackFormat(0))
                        muxer.start()
                    }
                    var last = 0L
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        last = ex.sampleTime
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, offsetUs + last, flags)
                        muxer.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                    // AAC の1フレーム = 1024 サンプル
                    offsetUs += last + 1024L * 1_000_000L / SAMPLE_RATE
                } finally {
                    ex.release()
                }
                onProgress((n + 1).toFloat() / files.size)
            }
        } finally {
            if (track >= 0) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
