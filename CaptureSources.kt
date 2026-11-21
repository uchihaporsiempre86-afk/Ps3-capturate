package com.ps3capture.app

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Byte stream source. read() returns -1 at end of stream. close() must be safe from another thread. */
interface CaptureSource {
    @Throws(IOException::class) fun open()
    @Throws(IOException::class) fun read(buf: ByteArray): Int
    fun close()
}

/** USB Accessory (AOA). Requires the PS3 to have started accessory mode (see docs/USB_FEASIBILITY.md). */
class AccessorySource(
    private val usb: UsbManager,
    private val accessory: UsbAccessory
) : CaptureSource {
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null

    override fun open() {
        val p = usb.openAccessory(accessory) ?: throw IOException("openAccessory returned null")
        pfd = p
        input = FileInputStream(p.fileDescriptor)
    }

    /** buf.size must be >= 16384: reading less can lose data in the accessory driver. */
    override fun read(buf: ByteArray): Int {
        require(buf.size >= Proto.READ_BUF)
        return (input ?: throw IOException("closed")).read(buf, 0, buf.size)
    }

    override fun close() {
        try { input?.close() } catch (_: IOException) {}
        try { pfd?.close() } catch (_: IOException) {}
        input = null
        pfd = null
    }
}

/** Intermediate milestone: PS3 (client) connects via TCP to the phone (server). Same protocol. */
class TcpSource(private val port: Int) : CaptureSource {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    override fun open() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        val c = s.accept() // blocks until PS3 connects; close() interrupts it
        c.tcpNoDelay = true
        client = c
    }

    override fun read(buf: ByteArray): Int =
        (client ?: throw IOException("no client")).getInputStream().read(buf, 0, buf.size)

    override fun close() {
        try { client?.close() } catch (_: IOException) {}
        try { server?.close() } catch (_: IOException) {}
        client = null
        server = null
    }
}

/** Receiver thread: opens the source, reads in a loop, feeds the parser, retries if needed. */
class ReceiverThread(
    private val factory: () -> CaptureSource,
    private val parser: StreamParser,
    private val retry: Boolean,
    private val status: (String) -> Unit
) : Thread("ps3-rx") {

    @Volatile private var stopFlag = false
    @Volatile private var source: CaptureSource? = null

    override fun run() {
        val buf = ByteArray(Proto.READ_BUF)
        while (!stopFlag) {
            val s = factory()
            source = s
            try {
                status("Waiting for connection...")
                s.open()
                parser.reset()
                status("Connected")
                while (!stopFlag) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (n > 0) parser.feed(buf, n)
                }
                if (!stopFlag) status("Stream ended")
            } catch (e: IOException) {
                if (!stopFlag) status("Error: ${e.message}")
            } finally {
                s.close()
            }
            if (!retry || stopFlag) break
            try { sleep(500) } catch (_: InterruptedException) { break }
        }
        status("Stopped")
    }

    fun shutdown() {
        stopFlag = true
        source?.close()
        interrupt()
    }
}

/**
 * DEMO mode: generates internally the same binary stream the PS3 would send (color bars + moving box,
 * 720x480 RGB565 LE, 30 FPS, chunked with header and CRC) and delivers it in small pieces.
 * Exercises parser, drawing and recording end-to-end without PC or PS3. Does NOT validate network or USB.
 */
class DemoSource : CaptureSource {
    private val w = 720
    private val h = 480
    private val frame = ByteArray(w * h * 2)
    private var packet = ByteArray(0)
    private var pos = 0
    private var frameId = 0L
    private var offset = 0
    private var nextAtNs = 0L
    private val crc = CRC32()
    @Volatile private var closed = false

    override fun open() {
        closed = false
        frameId = 0
        offset = frame.size
        pos = 0
        packet = ByteArray(0)
        nextAtNs = System.nanoTime()
    }

    override fun read(buf: ByteArray): Int {
        if (closed) return -1
        if (pos >= packet.size) buildNextPacket()
        if (closed) return -1
        val n = minOf(minOf(buf.size, 4096), packet.size - pos) // small pieces: stress the parser
        System.arraycopy(packet, pos, buf, 0, n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
    }

    private fun buildNextPacket() {
        if (offset >= frame.size) {
            val waitMs = (nextAtNs - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    closed = true
                    return
                }
            }
            nextAtNs += 33_333_333L
            frameId++
            render(frameId)
            offset = 0
        }
        val len = minOf(frame.size - offset, Proto.READ_BUF - Proto.HEADER_SIZE - 4)
        val bb = ByteBuffer.allocate(Proto.HEADER_SIZE + len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(Proto.MAGIC.toInt())
        bb.put(Proto.VERSION.toByte())
        bb.put(Proto.T_CHUNK.toByte())
        bb.putShort(Proto.HEADER_SIZE.toShort())
        bb.putInt(frameId.toInt())
        bb.putInt(frame.size)
        bb.putInt(offset)
        bb.putInt(len)
        bb.putShort(w.toShort())
        bb.putShort(h.toShort())
        bb.putShort(Proto.FMT_RGB565_LE.toShort())
        bb.putShort((if (offset + len == frame.size) 1 else 0).toShort())
        bb.putInt((frameId * 33).toInt())
        val arr = bb.array()
        crc.reset()
        crc.update(arr, 0, 36)
        bb.putInt(crc.value.toInt())
        bb.put(frame, offset, len)
        packet = arr
        pos = 0
        offset += len
    }

    /** Same pattern as PS3/common/testpattern.c. */
    private fun render(id: Long) {
        val bars = arrayOf(
            intArrayOf(255, 255, 255), intArrayOf(255, 255, 0), intArrayOf(0, 255, 255), intArrayOf(0, 255, 0),
            intArrayOf(255, 0, 255), intArrayOf(255, 0, 0), intArrayOf(0, 0, 255), intArrayOf(0, 0, 0)
        )
        val bx = ((id * 6) % (w - 64)).toInt()
        val by = ((id * 3) % (h - 64)).toInt()
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r: Int
                val g: Int
                val b: Int
                if (x >= bx && x < bx + 64 && y >= by && y < by + 64) {
                    r = 255; g = 128; b = 0
                } else {
                    val c = bars[(x * 8) / w]
                    r = c[0]; g = c[1]; b = c[2]
                }
                val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                frame[o] = (v and 0xFF).toByte()
                frame[o + 1] = (v shr 8).toByte()
                o += 2
            }
        }
    }
}
