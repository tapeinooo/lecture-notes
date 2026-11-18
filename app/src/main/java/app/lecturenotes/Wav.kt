package app.lecturenotes

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16000

object Wav {
    fun header(dataLen: Long, rate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(channels.toShort())
        b.putInt(rate)
        b.putInt(rate * 2 * channels)
        b.putShort((2 * channels).toShort())
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }

    /** 書きながら作る WAV。close で大きさを書き込む */
    class Writer(val file: File) {
        private val raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(header(0))
        }
        var dataLen = 0L
            private set

        fun write(pcm: ShortArray, n: Int) {
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = pcm[i].toInt()
                bytes[i * 2] = (v and 0xff).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            raf.write(bytes)
            dataLen += bytes.size
        }

        fun close() {
            raf.seek(0)
            raf.write(header(dataLen))
            raf.close()
        }
    }

    /** 区間ごとの WAV を1つにまとめる */
    fun merge(files: List<File>, out: File, onProgress: (Float) -> Unit = {}) {
        val total = files.sumOf { (it.length() - 44).coerceAtLeast(0) }
        out.outputStream().buffered().use { o ->
            o.write(header(total))
            var done = 0L
            val buf = ByteArray(64 * 1024)
            files.forEach { f ->
                f.inputStream().buffered().use { i ->
                    i.skip(44)
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        done += n
                    }
                }
                if (total > 0) onProgress(done.toFloat() / total)
            }
        }
    }
}
