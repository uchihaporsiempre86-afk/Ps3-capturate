package com.ps3capture.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * H.264 recording -> MP4 with MediaCodec (Surface input) + MediaMuxer.
 * Timestamps are set by the encoder when each frame arrives (arrival time on Android).
 */
class Recorder(
    val file: File,
    private val width: Int,
    private val height: Int,
    fps: Int = 30,
    bitrate: Int = 6_000_000
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private var stopping = false
    private val rect = Rect(0, 0, width, height)
    private val drain: Thread

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        drain = Thread({ drainLoop() }, "ps3-rec-drain").also { it.start() }
    }

    /** Call from the drawing thread. Ignores frames of different size. */
    @Synchronized
    fun drawFrame(bmp: Bitmap) {
        if (stopping || bmp.width != width || bmp.height != height) return
        val c = lockSafely() ?: return
        try {
            c.drawBitmap(bmp, null, rect, null)
        } finally {
            surface.unlockCanvasAndPost(c)
        }
    }

    private fun lockSafely(): android.graphics.Canvas? {
        return try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            null
        }
    }

    /** Blocks until the MP4 is closed. Do NOT call from the UI thread. */
    fun stop() {
        synchronized(this) {
            if (stopping) return
            stopping = true
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {}
        }
        drain.join(5000)
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { surface.release() } catch (_: Exception) {}
        }
    }
}
