package com.ps3capture.app

import java.util.zip.CRC32

/** Protocol v2 constants. Mirror of PS3/common/pcap_protocol.h. Little-endian on the wire. */
object Proto {
    const val MAGIC = 0x32504350L // 'P','C','P','2'
    const val VERSION = 2
    const val HEADER_SIZE = 40
    const val T_HELLO = 1
    const val T_CHUNK = 2
    const val T_PING = 3
    const val FMT_RGB565_LE = 1
    /** Android accessory driver max read size is 16384 bytes. */
    const val READ_BUF = 16384
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
}

/**
 * Reassembles frames from a byte STREAM with arbitrary cuts.
 * Never assumes one read() equals one packet. Resyncs by searching for magic
 * and validating the CRC32 of the header. Uses no Android classes (JUnit/JVM safe).
 */
class StreamParser(private val sink: Sink) {

    interface Sink {
        /** Called on the receiver thread. `pixels` is REUSED: copy before returning if needed. */
        fun onFrame(frameId: Long, width: Int, height: Int, pixels: ByteArray, ptsMs: Long)
        fun onHello(width: Int, height: Int) {}
    }

    @Volatile var framesOk = 0L
        private set
    @Volatile var framesDropped = 0L
        private set
    @Volatile var resyncs = 0L
        private set

    private val acc = ByteArray(1 shl 17)
    private var accLen = 0
    private val crc = CRC32()

    private var frame = ByteArray(0)
    private var active = false
    private var curId = -1L
    private var got = 0
    private var total = 0

    fun reset() {
        accLen = 0
        active = false
        curId = -1L
        got = 0
        total = 0
    }

    fun feed(src: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val take = minOf(acc.size - accLen, n - off)
            System.arraycopy(src, off, acc, accLen, take)
            accLen += take
            off += take
            drain()
        }
    }

    private fun u8(o: Int) = acc[o].toInt() and 0xFF
    private fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
    private fun u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)

    private fun drain() {
        var pos = 0
        while (accLen - pos >= Proto.HEADER_SIZE) {
            // 1) valid header?
            if (u32(pos) != Proto.MAGIC || !headerCrcOk(pos) || !fieldsOk(pos)) {
                pos++
                resyncs++
                continue
            }
            val type = u8(pos + 5)
            val chunkLen = if (type == Proto.T_CHUNK) u32(pos + 20).toInt() else 0
            val need = Proto.HEADER_SIZE + chunkLen
            if (accLen - pos < need) break // missing payload: wait for more bytes

            when (type) {
                Proto.T_HELLO -> sink.onHello(u16(pos + 24), u16(pos + 26))
                Proto.T_CHUNK -> handleChunk(pos, chunkLen)
            }
            pos += need
        }
        if (pos > 0) {
            System.arraycopy(acc, pos, acc, 0, accLen - pos)
            accLen -= pos
        }
    }

    private fun headerCrcOk(p: Int): Boolean {
        crc.reset()
        crc.update(acc, p, 36)
        return crc.value == u32(p + 36)
    }

    private fun fieldsOk(p: Int): Boolean {
        if (u8(p + 4) != Proto.VERSION || u16(p + 6) != Proto.HEADER_SIZE) return false
        val type = u8(p + 5)
        if (type < Proto.T_HELLO || type > Proto.T_PING) return false
        if (type != Proto.T_CHUNK) return true
        val w = u16(p + 24)
        val h = u16(p + 26)
        val frameBytes = u32(p + 12)
        val off = u32(p + 16)
        val len = u32(p + 20)
        if (u16(p + 28) != Proto.FMT_RGB565_LE) return false
        if (w < 16 || w > 1920 || h < 16 || h > 1080) return false
        if (frameBytes != w.toLong() * h * 2 || frameBytes > Proto.MAX_FRAME_BYTES) return false
        if (len == 0L || len > Proto.READ_BUF) return false
        return off + len <= frameBytes
    }

    private fun handleChunk(p: Int, len: Int) {
        val id = u32(p + 8)
        val frameBytes = u32(p + 12).toInt()
        val off = u32(p + 16).toInt()
        val w = u16(p + 24)
        val h = u16(p + 26)

        if (!active || id != curId) {
            if (active && got != total) framesDropped++ // previous incomplete
            active = (off == 0)
            curId = id
            got = 0
            total = frameBytes
            if (active && frame.size != frameBytes) frame = ByteArray(frameBytes)
        }
        if (!active) return
        if (off != got) {
            active = false
            framesDropped++
            return // gap: drop frame
        }
        System.arraycopy(acc, p + Proto.HEADER_SIZE, frame, off, len)
        got += len
        if (got == total) {
            framesOk++
            active = false
            curId = -1L
            got = 0
            total = 0
            sink.onFrame(id, w, h, frame, u32(p + 32))
        }
    }
}
