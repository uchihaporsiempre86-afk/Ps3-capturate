package com.ps3capture.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), SurfaceHolder.Callback {

    private enum class Mode { DEMO, TCP, USB }

    companion object {
        const val ACTION_PERMISSION = "com.ps3capture.app.USB_PERMISSION"
        const val TCP_PORT = 5000
    }

    private lateinit var usb: UsbManager
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var modeButton: Button
    private lateinit var recordButton: Button

    private var mode = Mode.DEMO // starts in demo: verify the app without PC or PS3
    private var surfaceReady = false
    private var presenter: FramePresenter? = null
    private var parser: StreamParser? = null
    private var rx: ReceiverThread? = null
    private var recorder: Recorder? = null

    @Volatile private var statusText = "Idle"
    private val ui = Handler(Looper.getMainLooper())
    private var lastPresented = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        restartPipeline()
                    } else {
                        setStatus("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    setStatus("USB accessory disconnected")
                    stopPipeline()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()

        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }

        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            mode = Mode.USB
        }
        updateButtons()
        ui.post(statsTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            mode = Mode.USB
            updateButtons()
            restartPipeline()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(statsTick)
        stopPipeline()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        restartPipeline()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopPipeline()
    }

    // ---------------------------------------------------------------- pipeline
    private fun restartPipeline() {
        stopPipeline()
        if (!surfaceReady) return

        val factory: () -> CaptureSource
        val retry: Boolean
        when (mode) {
            Mode.DEMO -> {
                factory = { DemoSource() }
                retry = false
                setStatus("Demo: internal test stream")
            }
            Mode.TCP -> {
                factory = { TcpSource(TCP_PORT) }
                retry = true
                setStatus("Wi-Fi: listening on ${localIp()}:$TCP_PORT")
            }
            Mode.USB -> {
                val acc: UsbAccessory? = usb.accessoryList?.firstOrNull()
                if (acc == null) {
                    setStatus("USB: no accessory. Connect the PS3.")
                    return
                }
                if (!usb.hasPermission(acc)) {
                    val pi = PendingIntent.getBroadcast(
                        this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                        PendingIntent.FLAG_MUTABLE // system adds extras: must be mutable
                    )
                    usb.requestPermission(acc, pi)
                    setStatus("USB: requesting permission...")
                    return
                }
                factory = { AccessorySource(usb, acc) }
                retry = false
            }
        }

        val p = FramePresenter(surfaceView.holder)
        val sp = StreamParser(p)
        val r = ReceiverThread(factory, sp, retry) { setStatus(it) }
        presenter = p
        parser = sp
        rx = r
        p.start()
        r.start()
    }

    private fun stopPipeline() {
        stopRecording()
        rx?.shutdown()
        rx = null
        presenter?.shutdown()
        presenter = null
        parser = null
    }

    // ---------------------------------------------------------------- recording
    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        val p = presenter
        if (p == null || p.frameWidth == 0) {
            setStatus("No image to record yet")
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val name = "ps3_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        try {
            val r = Recorder(File(dir, name), p.frameWidth, p.frameHeight)
            recorder = r
            p.recorder = r
            recordButton.text = "Stop"
            setStatus("Recording: ${r.file.absolutePath}")
        } catch (e: Exception) {
            setStatus("Could not start recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        presenter?.recorder = null
        recordButton.text = "Record"
        Thread({
            r.stop()
            setStatus("Recording saved to ${r.file.absolutePath}")
        }, "ps3-rec-stop").start()
    }

    // ---------------------------------------------------------------- UI
    private fun setStatus(s: String) {
        statusText = s
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val p = presenter
            val sp = parser
            val fps = if (p != null) (p.presented - lastPresented) else 0L
            lastPresented = p?.presented ?: 0L
            statusView.text = buildString {
                append(statusText)
                if (p != null && sp != null) {
                    append("\n${p.frameWidth}x${p.frameHeight} FPS: $fps ok: ${sp.framesOk} ")
                    append("dropped: ${sp.framesDropped} resync: ${sp.resyncs}")
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateButtons() {
        modeButton.text = when (mode) {
            Mode.DEMO -> "Source: Demo"
            Mode.TCP -> "Source: Wi-Fi"
            Mode.USB -> "Source: USB"
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(
            surfaceView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 12f
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        modeButton = Button(this).apply {
            setOnClickListener {
                mode = when (mode) {
                    Mode.DEMO -> Mode.TCP
                    Mode.TCP -> Mode.USB
                    Mode.USB -> Mode.DEMO
                }
                updateButtons()
                restartPipeline()
            }
        }
        recordButton = Button(this).apply {
            text = "Record"
            setOnClickListener { toggleRecording() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            addView(modeButton)
            addView(recordButton)
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
