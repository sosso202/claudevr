package com.lyes.vrclaude

import android.content.Context
import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hand tracking from ARCore's CPU camera image with MediaPipe's hand landmarker.
 * Landmarks are lifted to 3D world space with the camera intrinsics and an
 * estimated depth from the hand's size in the image.
 */
class HandTracker(context: Context) {

    class Hand(
        val time: Long,
        val points: Array<FloatArray>, // 21 world-space points
        val pinching: Boolean,
        val pinch: FloatArray          // world-space pinch point
    )

    @Volatile var latest: Hand? = null
    @Volatile var error: String? = null
    @Volatile var ready = false

    private val busy = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var pinchState = false

    // Copied camera data (reused; only touched while busy is held).
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null

    init {
        val app = context.applicationContext
        exec.execute {
            landmarker = create(app, Delegate.GPU) ?: create(app, Delegate.CPU)
            ready = landmarker != null
        }
    }

    private fun create(ctx: Context, delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        HandLandmarker.createFromOptions(ctx, opts)
    } catch (t: Throwable) {
        Log.w(TAG, "Landmarker ($delegate) failed", t)
        error = t.message ?: t.toString()
        null
    }

    /** Called on the GL thread after session.update(). Drops frames while busy. */
    fun submit(frame: Frame, camera: Camera) {
        if (!ready || !busy.compareAndSet(false, true)) return
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            busy.set(false); return
        }
        val w: Int; val h: Int; val yRow: Int; val uvRow: Int; val uvPix: Int
        try {
            w = image.width; h = image.height
            val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
            yRow = yp.rowStride; uvRow = up.rowStride; uvPix = up.pixelStride
            yBytes = copy(yp.buffer, yBytes)
            uBytes = copy(up.buffer, uBytes)
            vBytes = copy(vp.buffer, vBytes)
        } catch (e: Exception) {
            image.close(); busy.set(false); return
        }
        image.close()

        val pose = FloatArray(16)
        camera.pose.toMatrix(pose, 0)
        val intr = camera.imageIntrinsics
        val dims = intr.imageDimensions
        val sx = if (dims[0] > 0) w.toFloat() / dims[0] else 1f
        val sy = if (dims[1] > 0) h.toFloat() / dims[1] else 1f
        val fx = intr.focalLength[0] * sx
        val fy = intr.focalLength[1] * sy
        val cx = intr.principalPoint[0] * sx
        val cy = intr.principalPoint[1] * sy

        exec.execute {
            try {
                process(w, h, yRow, uvRow, uvPix, pose, fx, fy, cx, cy)
            } catch (t: Throwable) {
                Log.w(TAG, "Hand processing failed", t)
                error = t.message ?: t.toString()
            } finally {
                busy.set(false)
            }
        }
    }

    private fun copy(buf: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        buf.rewind()
        val n = buf.remaining()
        val out = if (reuse.size >= n) reuse else ByteArray(n)
        buf.get(out, 0, n)
        return out
    }

    private fun process(
        w: Int, h: Int, yRow: Int, uvRow: Int, uvPix: Int,
        pose: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float
    ) {
        val lm = landmarker ?: return
        // Half-resolution RGB conversion: the model works at ~224 px anyway.
        val ow = w / 2; val oh = h / 2
        if (pixels.size != ow * oh) pixels = IntArray(ow * oh)
        var i = 0
        for (y in 0 until oh) {
            val syy = y * 2
            val yBase = syy * yRow
            val uvBase = (syy / 2) * uvRow
            for (x in 0 until ow) {
                val sxx = x * 2
                val yy = yBytes[yBase + sxx].toInt() and 0xff
                val idx = uvBase + (sxx / 2) * uvPix
                val u = (uBytes[idx].toInt() and 0xff) - 128
                val v = (vBytes[idx].toInt() and 0xff) - 128
                var r = (yy + 1.402f * v).toInt()
                var g = (yy - 0.344f * u - 0.714f * v).toInt()
                var b = (yy + 1.772f * u).toInt()
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b
                pixels[i++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        var bmp = bitmap
        if (bmp == null || bmp.width != ow || bmp.height != oh) {
            bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bitmap = bmp
        }
        bmp!!.setPixels(pixels, 0, ow, 0, 0, ow, oh)

        val result = lm.detect(BitmapImageBuilder(bmp).build())
        val hands = result.landmarks()
        if (hands.isEmpty()) return
        val l = hands[0]
        if (l.size < 21) return

        // Pixel coordinates in the full-resolution CPU image.
        val px = FloatArray(21) { l[it].x() * w }
        val py = FloatArray(21) { l[it].y() * h }
        fun d2(a: Int, b: Int) = hypot(px[a] - px[b], py[a] - py[b])

        val palm = max(d2(5, 17), 1f)        // index MCP to pinky MCP, ~7 cm
        val length = max(d2(0, 9), 1f)       // wrist to middle MCP, ~9 cm
        val z0 = min(fx * 0.07f / palm, fx * 0.09f / length).coerceIn(0.12f, 1.2f)

        val pts = Array(21) { FloatArray(3) }
        val cam = FloatArray(4)
        val world = FloatArray(4)
        for (k in 0 until 21) {
            val z = (z0 + l[k].z() * w * z0 / fx).coerceAtLeast(0.08f)
            cam[0] = (px[k] - cx) / fx * z
            cam[1] = -(py[k] - cy) / fy * z
            cam[2] = -z
            cam[3] = 1f
            Matrix.multiplyMV(world, 0, pose, 0, cam, 0)
            pts[k][0] = world[0]; pts[k][1] = world[1]; pts[k][2] = world[2]
        }

        val ratio = d2(4, 8) / max(palm, length * 0.75f)
        pinchState = if (pinchState) ratio < 0.5f else ratio < 0.3f
        val pinch = FloatArray(3) { (pts[4][it] + pts[8][it]) * 0.5f }
        latest = Hand(SystemClock.elapsedRealtime(), pts, pinchState, pinch)
    }

    fun close() {
        exec.execute {
            try { landmarker?.close() } catch (_: Throwable) {}
            landmarker = null
            ready = false
        }
        exec.shutdown()
    }

    companion object { private const val TAG = "VRClaudeHands" }
}
