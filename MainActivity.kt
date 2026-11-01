package com.lyes.vrclaude

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.cardboard.sdk.HeadTracker
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.QrCode

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var status: TextView
    private var headTracker: HeadTracker? = null
    private var session: Session? = null
    private var installRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.9f
        window.attributes = lp

        // Cardboard SDK: must be initialized before any other Cardboard call.
        try {
            Initialize.initialize(this)
            headTracker = HeadTracker()
        } catch (t: Throwable) {
            Log.e("VRClaude", "Cardboard init failed", t)
        }

        glView = GLSurfaceView(this)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = VrRenderer(this, headTracker)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 16)
        }

        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        if (headTracker == null) setStatus("Cardboard SDK failed to start.")

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { renderer.tap(); return true }
            override fun onLongPress(e: MotionEvent) { renderer.toggleStereo() }
        })
        glView.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        headTracker?.resume()
        renderer.reloadViewer() // picks up a viewer scanned while we were away
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(this)
                val cfg = Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                }
                s.configure(cfg)
                session = s
                renderer.session = s
            } catch (e: UnavailableException) {
                setStatus("ARCore is not available: ${e.javaClass.simpleName}. Install Google Play Services for AR.")
            } catch (e: Exception) {
                setStatus("Could not start AR: ${e.message}")
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            setStatus("Camera not available. Close other camera apps and reopen.")
            session = null
            renderer.session = null
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        session?.pause()
        headTracker?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.hands.close()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
            setStatus("Camera permission is needed for tracking and passthrough.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { renderer.recenter(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { renderer.tap(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /** Opens the Cardboard SDK's QR scanner for the viewer's lens profile. */
    fun scanViewer() = runOnUiThread {
        try { QrCode.scanQrCodeAndSaveDeviceParams() } catch (t: Throwable) { setStatus("QR scanner failed: ${t.message}") }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Suppress("DEPRECATION")
    fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    /** Cardboard's viewport orientation: 0 = landscape left, 1 = landscape right. */
    fun cardboardOrientation(): Int = if (displayRotation() == Surface.ROTATION_270) 1 else 0

    fun setStatus(text: String) = runOnUiThread { status.text = text }
}
