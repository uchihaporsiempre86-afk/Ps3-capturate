package com.ps3capture.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock

/**
 * Receives frames (receiver thread) and draws them (own thread). "Last frame wins" policy:
 * if drawing is slower than reception, frames are dropped instead of accumulating latency.
 * Two reusable bitmaps: no allocations per frame.
 */
class FramePresenter(private val holder: SurfaceHolder) : Thread("ps3-present"), StreamParser.Sink {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pending: Bitmap? = null
    private var drawing: Bitmap? = null
    private var dirty = false
    private var wrapped: ByteBuffer? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private val src = Rect()

    @Volatile private var running = true
    @Volatile var recorder: Recorder? = null
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set
    @Volatile var presented = 0L
        private set

    override fun onFrame(frameId: Long, width: Int, height: Int, pixels: ByteArray, ptsMs: Long) {
        lock.lock()
        try {
            var bmp = pending
            if (bmp == null || bmp.width != width || bmp.height != height) {
                bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                pending = bmp
            }
            var bb = wrapped
            if (bb == null || bb.array() !== pixels) {
                bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN)
                wrapped = bb
            }
            bb.rewind()
            bmp!!.copyPixelsFromBuffer(bb) // RGB565 little-endian == RGB_565 memory format
            frameWidth = width
            frameHeight = height
            dirty = true
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Wait for a new frame; returns null if we should stop. */
    private fun awaitFrame(): Bitmap? {
        lock.lock()
        try {
            while (!dirty && running) {
                changed.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (!running) return null
            val t = pending
            pending = drawing
            drawing = t
            dirty = false
            return drawing
        } catch (e: InterruptedException) {
            return null
        } finally {
            lock.unlock()
        }
    }

    override fun run() {
        while (running) {
            val b = awaitFrame() ?: continue
            drawPreview(b)
            recorder?.drawFrame(b)
            presented++
        }
    }

    private fun lockCanvasSafely(): Canvas? {
        return try {
            holder.lockCanvas()
        } catch (e: Exception) {
            null
        }
    }

    private fun drawPreview(b: Bitmap) {
        val c = lockCanvasSafely() ?: return
        try {
            c.drawColor(Color.BLACK)
            val vw = c.width
            val vh = c.height
            val scale = minOf(vw.toFloat() / b.width, vh.toFloat() / b.height)
            val w = (b.width * scale).toInt()
            val h = (b.height * scale).toInt()
            dst.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
            src.set(0, 0, b.width, b.height)
            c.drawBitmap(b, src, dst, paint)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    fun shutdown() {
        running = false
        lock.lock()
        try {
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
