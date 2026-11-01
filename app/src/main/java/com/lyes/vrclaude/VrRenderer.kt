package com.lyes.vrclaude

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class VrRenderer(private val act: MainActivity, private val screenWidthMm: Float) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    val hands = HandTracker(act)

    private val taps = AtomicInteger(0)
    @Volatile private var stereoRequest = true
    @Volatile private var recenterRequest = false
    private var stereo = true

    fun tap() { taps.incrementAndGet() }
    fun toggleStereo() { stereoRequest = !stereoRequest }
    fun recenter() { recenterRequest = true }

    // ---------------- scene state ----------------
    private enum class Mode { CUBES, PAINT }

    private class Cube(val pos: FloatArray, val size: Float, val color: FloatArray) {
        val vel = FloatArray(3)
        var yaw = 0f
        var spin = 0f
    }

    private class Stroke(val color: FloatArray) {
        val pts = ArrayList<Float>()
        var buf: FloatBuffer? = null
        var dirty = true
        val count get() = pts.size / 3
    }

    private var mode = Mode.CUBES
    private var passthrough = true
    private val cubes = ArrayList<Cube>()
    private val strokes = ArrayList<Stroke>()
    private var activeStroke: Stroke? = null
    private var held: Cube? = null
    private val history = ArrayDeque<Pair<Long, FloatArray>>()
    private var pinchPrev = false
    private var handVisible = false
    private var hand: HandTracker.Hand? = null

    private val colorNames = arrayOf("Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "White")
    private val colors = arrayOf(
        floatArrayOf(0.95f, 0.26f, 0.21f, 1f), floatArrayOf(1f, 0.6f, 0.1f, 1f),
        floatArrayOf(1f, 0.92f, 0.23f, 1f), floatArrayOf(0.3f, 0.85f, 0.4f, 1f),
        floatArrayOf(0.2f, 0.85f, 0.95f, 1f), floatArrayOf(0.25f, 0.45f, 1f, 1f),
        floatArrayOf(0.7f, 0.35f, 0.95f, 1f), floatArrayOf(1f, 1f, 1f, 1f)
    )
    private var colorIndex = 0
    private var cubeColorIndex = 0

    // Panel (4 buttons) in world space.
    private var panelPlaced = false
    private val panelPos = FloatArray(3)
    private val panelDir = FloatArray(3)   // horizontal, from user to panel
    private val panelRight = FloatArray(3)
    private var hovered = -1
    private val btnW = 0.22f
    private val btnH = 0.07f
    private val btnX = floatArrayOf(-0.36f, -0.12f, 0.12f, 0.36f)
    private val btnTex = IntArray(4)
    private var labelsDirty = true

    private val gazeO = FloatArray(3)
    private val gazeF = FloatArray(3)
    private var floorY = -1.4f
    private var planes: List<Plane> = emptyList()

    // ---------------- GL state ----------------
    private var bgProg = 0; private var colProg = 0; private var texProg = 0; private var disProg = 0
    private var camTex = 0
    private var boundSession: Session? = null
    private var geomDirty = true
    private var w = 1; private var h = 1; private var eyeW = 1
    private var fbo = 0; private var fboTex = 0; private var fboDepth = 0; private var fboW = 0; private var fboH = 0

    private val quadNdc = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUv = GlUtil.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val unitQuad = GlUtil.floatBuffer(floatArrayOf(-.5f, -.5f, 0f, .5f, -.5f, 0f, -.5f, .5f, 0f, .5f, .5f, 0f))
    private val labelUv = GlUtil.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val floorQuad = GlUtil.floatBuffer(floatArrayOf(-30f, 0f, -30f, 30f, 0f, -30f, -30f, 0f, 30f, 30f, 0f, 30f))
    private lateinit var cubePos: FloatBuffer
    private lateinit var cubeNrm: FloatBuffer
    private val pointBuf = GlUtil.floatBuffer(3 * 64)
    private var planeBuf = GlUtil.floatBuffer(3 * 256)

    // color program locations
    private var cVP = 0; private var cModel = 0; private var cPointScale = 0; private var cPos = 0; private var cNrm = 0
    private var cColor = 0; private var cLit = 0; private var cChecker = 0; private var cRound = 0
    // texture program
    private var tMVP = 0; private var tPos = 0; private var tUv = 0; private var tTex = 0; private var tHi = 0
    // background
    private var bPos = 0; private var bUv = 0; private var bTex = 0
    // distortion
    private var dPos = 0; private var dTex = 0; private var dAspect = 0; private var dK = 0; private var dScale = 0; private var dCenter = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var lastTime = 0L
    private var lastStatus = ""
    private var hasPose = false
    private var lastTracked = 0L

    // Viewer lens: distortion coefficients and inter-lens distance (Cardboard-style).
    private val k1 = 0.22f
    private val k2 = 0.12f
    private val lensSeparationMm = 63.5f
    private val ipd = 0.064f

    // ================= GL callbacks =================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        boundSession = null

        bgProg = GlUtil.program(GlUtil.BG_VS, GlUtil.BG_FS)
        bPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bUv = GLES20.glGetAttribLocation(bgProg, "aUv")
        bTex = GLES20.glGetUniformLocation(bgProg, "uTex")

        colProg = GlUtil.program(GlUtil.COLOR_VS, GlUtil.COLOR_FS)
        cVP = GLES20.glGetUniformLocation(colProg, "uVP")
        cModel = GLES20.glGetUniformLocation(colProg, "uModel")
        cPointScale = GLES20.glGetUniformLocation(colProg, "uPointScale")
        cColor = GLES20.glGetUniformLocation(colProg, "uColor")
        cLit = GLES20.glGetUniformLocation(colProg, "uLit")
        cChecker = GLES20.glGetUniformLocation(colProg, "uChecker")
        cRound = GLES20.glGetUniformLocation(colProg, "uRound")
        cPos = GLES20.glGetAttribLocation(colProg, "aPos")
        cNrm = GLES20.glGetAttribLocation(colProg, "aNormal")

        texProg = GlUtil.program(GlUtil.TEX_VS, GlUtil.TEX_FS)
        tMVP = GLES20.glGetUniformLocation(texProg, "uMVP")
        tTex = GLES20.glGetUniformLocation(texProg, "uTex")
        tHi = GLES20.glGetUniformLocation(texProg, "uHighlight")
        tPos = GLES20.glGetAttribLocation(texProg, "aPos")
        tUv = GLES20.glGetAttribLocation(texProg, "aUv")

        disProg = GlUtil.program(GlUtil.DIST_VS, GlUtil.DIST_FS)
        dPos = GLES20.glGetAttribLocation(disProg, "aPos")
        dTex = GLES20.glGetUniformLocation(disProg, "uTex")
        dAspect = GLES20.glGetUniformLocation(disProg, "uAspect")
        dK = GLES20.glGetUniformLocation(disProg, "uK")
        dScale = GLES20.glGetUniformLocation(disProg, "uScale")
        dCenter = GLES20.glGetUniformLocation(disProg, "uCenter")

        buildCube()
        fboW = 0; fboH = 0
        for (i in 0 until 4) btnTex[i] = 0
        labelsDirty = true
        for (s in strokes) s.dirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        geomDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val s = session ?: return
        if (boundSession !== s) {
            s.setCameraTextureName(camTex)
            boundSession = s
            geomDirty = true
        }
        if (stereo != stereoRequest) { stereo = stereoRequest; geomDirty = true }
        if (geomDirty) {
            eyeW = if (stereo) w / 2 else w
            s.setDisplayGeometry(act.displayRotation(), eyeW, h)
            geomDirty = false
        }
        if (labelsDirty) rebuildLabels()

        val frame: Frame = try { s.update() } catch (e: Exception) {
            Log.w(TAG, "update failed", e); return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quadNdc.position(0); quadUv.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc,
                Coordinates2d.TEXTURE_NORMALIZED, quadUv
            )
            quadNdc.position(0); quadUv.position(0)
        }
        if (frame.timestamp == 0L) return

        val cam = frame.camera
        val tracking = cam.trackingState == TrackingState.TRACKING
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTime == 0L) 0.016f else ((now - lastTime) / 1000f).coerceIn(0.001f, 0.05f)
        lastTime = now

        if (tracking) {
            hands.submit(frame, cam)
            cam.getProjectionMatrix(proj, 0, 0.05f, 100f)
            cam.getViewMatrix(view, 0)
            update(frame, cam, s, dt, now)
            hasPose = true
            lastTracked = now
        } else {
            taps.set(0)
        }
        // Brief tracking drops keep the scene (with the last pose); only long ones show the hint.
        updateStatus(tracking || (hasPose && now - lastTracked < 2000))
        val drawScene = hasPose

        if (stereo) {
            ensureFbo(eyeW, h)
            for (eye in 0..1) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glViewport(0, 0, eyeW, h)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                drawEye(eye, drawScene)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(eye * eyeW, 0, eyeW, h)
                drawDistortion(eye)
            }
        } else {
            GLES20.glViewport(0, 0, w, h)
            drawEye(-1, drawScene)
        }
    }

    // ================= logic =================

    private fun update(frame: Frame, cam: Camera, s: Session, dt: Float, now: Long) {
        val pose = cam.displayOrientedPose
        gazeO[0] = pose.tx(); gazeO[1] = pose.ty(); gazeO[2] = pose.tz()
        val z = pose.zAxis
        gazeF[0] = -z[0]; gazeF[1] = -z[1]; gazeF[2] = -z[2]

        // Planes and floor.
        val list = ArrayList<Plane>()
        var lowest = Float.MAX_VALUE
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            list.add(p)
            if (p.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                val y = p.centerPose.ty()
                if (y < gazeO[1] - 0.3f && y < lowest) lowest = y
            }
        }
        planes = list
        floorY = if (lowest != Float.MAX_VALUE) lowest else gazeO[1] - 1.4f

        // Panel follows lazily.
        if (recenterRequest || !panelPlaced || horizontalAngle(gazeF, panelDir) > 60f) {
            placePanel(); recenterRequest = false
        }
        hovered = gazeButton()

        // Hands.
        val hd = hands.latest
        handVisible = hd != null && now - hd.time < 350
        hand = if (handVisible) hd else null
        val pinchNow = hand?.pinching == true
        hand?.let { recordHistory(it.pinch, now) }
        if (pinchNow && !pinchPrev) onPinchStart(frame, hand!!)
        if (pinchNow) onPinchHold(hand!!)
        if (!pinchNow && pinchPrev) onPinchEnd(now)
        pinchPrev = pinchNow

        val n = taps.getAndSet(0)
        repeat(n) { onTap(frame) }

        physics(dt)
    }

    private fun recordHistory(p: FloatArray, now: Long) {
        history.addLast(Pair(now, p.copyOf()))
        while (history.isNotEmpty() && now - history.first().first > 150) history.removeFirst()
    }

    private fun onPinchStart(frame: Frame, hd: HandTracker.Hand) {
        if (hovered >= 0) { pressButton(hovered); return }
        if (mode == Mode.PAINT) {
            val st = Stroke(colors[colorIndex].copyOf())
            st.pts.add(hd.pinch[0]); st.pts.add(hd.pinch[1]); st.pts.add(hd.pinch[2])
            strokes.add(st); activeStroke = st
            return
        }
        // Grab the nearest cube to the hand, else one the gaze hits.
        var best: Cube? = null
        var bestD = 0.15f
        for (c in cubes) {
            val d = dist(c.pos, hd.pinch)
            if (d < bestD) { bestD = d; best = c }
        }
        if (best == null) best = gazeCube()
        if (best != null) {
            held = best
            best.vel.fill(0f); best.spin = 0f
            return
        }
        placeCube(frame)
    }

    private fun onPinchHold(hd: HandTracker.Hand) {
        held?.let { c ->
            c.pos[0] = hd.pinch[0]; c.pos[1] = hd.pinch[1]; c.pos[2] = hd.pinch[2]
        }
        activeStroke?.let { st ->
            val n = st.pts.size
            val dx = hd.pinch[0] - st.pts[n - 3]
            val dy = hd.pinch[1] - st.pts[n - 2]
            val dz = hd.pinch[2] - st.pts[n - 1]
            if (dx * dx + dy * dy + dz * dz > 0.004f * 0.004f && st.count < 4000) {
                st.pts.add(hd.pinch[0]); st.pts.add(hd.pinch[1]); st.pts.add(hd.pinch[2])
                st.dirty = true
            }
        }
    }

    private fun onPinchEnd(now: Long) {
        held?.let { c ->
            if (handVisible && history.size >= 2) {
                val a = history.first(); val b = history.last()
                val t = ((b.first - a.first) / 1000f).coerceAtLeast(0.02f)
                for (i in 0..2) c.vel[i] = ((b.second[i] - a.second[i]) / t).coerceIn(-8f, 8f)
                c.spin = (c.vel[0] - c.vel[2]) * 3f
            } else {
                c.vel.fill(0f) // hand lost: leave it where it was
            }
        }
        held = null
        activeStroke = null
    }

    private fun onTap(frame: Frame) {
        if (hovered >= 0) { pressButton(hovered); return }
        if (mode == Mode.CUBES) placeCube(frame)
    }

    private fun pressButton(i: Int) {
        when (i) {
            0 -> mode = if (mode == Mode.CUBES) Mode.PAINT else Mode.CUBES
            1 -> passthrough = !passthrough
            2 -> colorIndex = (colorIndex + 1) % colors.size
            3 -> if (mode == Mode.CUBES) { cubes.clear(); held = null } else { strokes.clear(); activeStroke = null }
        }
        labelsDirty = true
    }

    private fun placeCube(frame: Frame) {
        val size = 0.1f
        val color = colors[cubeColorIndex % colors.size].copyOf()
        cubeColorIndex++
        try {
            for (hit in frame.hitTest(eyeW / 2f, h / 2f)) {
                val t = hit.trackable
                if (t is Plane && t.isPoseInPolygon(hit.hitPose) && hit.distance < 5f) {
                    val p = hit.hitPose
                    cubes.add(Cube(floatArrayOf(p.tx(), p.ty() + size * 0.5f + 0.02f, p.tz()), size, color))
                    trimCubes(); return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hitTest failed", e)
        }
        // No surface under the gaze: drop it 0.6 m ahead.
        cubes.add(Cube(floatArrayOf(gazeO[0] + gazeF[0] * 0.6f, gazeO[1] + gazeF[1] * 0.6f, gazeO[2] + gazeF[2] * 0.6f), size, color))
        trimCubes()
    }

    private fun trimCubes() { while (cubes.size > 40) cubes.removeAt(0) }

    private fun supportHeight(c: Cube): Float {
        var best = floorY
        for (p in planes) {
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val py = p.centerPose.ty()
            if (py > best && py <= c.pos[1] + 0.02f && p.isPoseInPolygon(Pose.makeTranslation(c.pos[0], py, c.pos[2]))) best = py
        }
        return best
    }

    private fun physics(dt: Float) {
        for (c in cubes) {
            if (c === held) continue
            c.vel[1] -= 9.8f * dt
            for (i in 0..2) c.pos[i] += c.vel[i] * dt
            c.yaw += c.spin * dt
            val ground = supportHeight(c) + c.size * 0.5f
            if (c.pos[1] < ground) {
                c.pos[1] = ground
                c.vel[1] = if (abs(c.vel[1]) > 0.5f) -c.vel[1] * 0.35f else 0f
                c.vel[0] *= 0.85f; c.vel[2] *= 0.85f
                c.spin *= 0.85f
            }
        }
        // Cubes push each other apart.
        for (i in cubes.indices) for (j in i + 1 until cubes.size) {
            val a = cubes[i]; val b = cubes[j]
            val dx = b.pos[0] - a.pos[0]; val dy = b.pos[1] - a.pos[1]; val dz = b.pos[2] - a.pos[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val minD = (a.size + b.size) * 0.5f
            if (d < minD && d > 1e-4f) {
                val push = (minD - d) / d
                val wa = if (a === held) 0f else if (b === held) 1f else 0.5f
                val wb = 1f - wa
                a.pos[0] -= dx * push * wa; a.pos[1] -= dy * push * wa; a.pos[2] -= dz * push * wa
                b.pos[0] += dx * push * wb; b.pos[1] += dy * push * wb; b.pos[2] += dz * push * wb
                if (a === held) for (k in 0..2) b.vel[k] += (if (k == 0) dx else if (k == 1) dy else dz) * push * 20f
                if (b === held) for (k in 0..2) a.vel[k] -= (if (k == 0) dx else if (k == 1) dy else dz) * push * 20f
            }
        }
    }

    private fun placePanel() {
        var fx = gazeF[0]; var fz = gazeF[2]
        val l = sqrt(fx * fx + fz * fz)
        if (l < 1e-3f) { fx = 0f; fz = -1f } else { fx /= l; fz /= l }
        panelDir[0] = fx; panelDir[1] = 0f; panelDir[2] = fz
        panelRight[0] = -fz; panelRight[1] = 0f; panelRight[2] = fx
        panelPos[0] = gazeO[0] + fx * 0.9f
        panelPos[1] = gazeO[1] - 0.25f
        panelPos[2] = gazeO[2] + fz * 0.9f
        panelPlaced = true
    }

    private fun horizontalAngle(a: FloatArray, b: FloatArray): Float {
        val la = sqrt(a[0] * a[0] + a[2] * a[2]); val lb = sqrt(b[0] * b[0] + b[2] * b[2])
        if (la < 1e-3f || lb < 1e-3f) return 0f
        val c = ((a[0] * b[0] + a[2] * b[2]) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }

    /** Index of the button under the gaze, or -1. Panel faces the user (normal = -panelDir). */
    private fun gazeButton(): Int {
        if (!panelPlaced) return -1
        val nx = -panelDir[0]; val nz = -panelDir[2]
        val denom = gazeF[0] * nx + gazeF[2] * nz
        if (abs(denom) < 1e-4f) return -1
        val t = ((panelPos[0] - gazeO[0]) * nx + (panelPos[2] - gazeO[2]) * nz) / denom
        if (t <= 0f) return -1
        val hx = gazeO[0] + gazeF[0] * t - panelPos[0]
        val hy = gazeO[1] + gazeF[1] * t - panelPos[1]
        val hz = gazeO[2] + gazeF[2] * t - panelPos[2]
        val lx = hx * panelRight[0] + hz * panelRight[2]
        for (i in 0 until 4) {
            if (abs(lx - btnX[i]) <= btnW * 0.5f && abs(hy) <= btnH * 0.5f) return i
        }
        return -1
    }

    private fun gazeCube(): Cube? {
        var best: Cube? = null
        var bestT = 4f
        for (c in cubes) {
            val ox = c.pos[0] - gazeO[0]; val oy = c.pos[1] - gazeO[1]; val oz = c.pos[2] - gazeO[2]
            val t = ox * gazeF[0] + oy * gazeF[1] + oz * gazeF[2]
            if (t <= 0f || t > bestT) continue
            val px = ox - gazeF[0] * t; val py = oy - gazeF[1] * t; val pz = oz - gazeF[2] * t
            val r = c.size * 0.75f
            if (px * px + py * py + pz * pz < r * r) { best = c; bestT = t }
        }
        return best
    }

    private fun updateStatus(tracking: Boolean) {
        val msg = when {
            !tracking -> "Point the camera at the floor and move the phone slowly to start tracking."
            hands.error != null && !hands.ready -> "Hand tracking failed: ${hands.error}. Tap the screen to click."
            !stereo -> "Phone view. Hold the screen for viewer view. Tap or pinch to click."
            else -> ""
        }
        if (msg != lastStatus) { lastStatus = msg; act.setStatus(msg) }
    }

    // ================= drawing =================

    private fun drawEye(eye: Int, tracking: Boolean) {
        if (passthrough || !tracking) {
            drawBackground()
        } else {
            GLES20.glClearColor(0.04f, 0.05f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        if (!tracking) return

        Matrix.setIdentityM(tmp, 0)
        if (eye == 0) Matrix.translateM(tmp, 0, ipd / 2f, 0f, 0f)
        if (eye == 1) Matrix.translateM(tmp, 0, -ipd / 2f, 0f, 0f)
        Matrix.multiplyMM(eyeView, 0, tmp, 0, view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, eyeView, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        if (!passthrough) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, floorY, 0f)
            drawColor(model, floorQuad, null, GLES20.GL_TRIANGLE_STRIP, 4, FLOOR, checker = true)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        drawPlanes()
        GLES20.glDepthMask(true)

        for (c in cubes) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, c.pos[0], c.pos[1], c.pos[2])
            Matrix.rotateM(model, 0, Math.toDegrees(c.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(model, 0, c.size, c.size, c.size)
            val col = if (c === held) GRAB else if (hand != null && dist(c.pos, hand!!.pinch) < 0.15f && mode == Mode.CUBES) GRAB_HINT else c.color
            drawColor(model, cubePos, cubeNrm, GLES20.GL_TRIANGLES, 36, col, lit = true)
        }

        val pxPerMeter = proj[5] * h / 2f
        for (st in strokes) {
            if (st.count == 0) continue
            if (st.dirty || st.buf == null) {
                val arr = FloatArray(st.pts.size) { st.pts[it] }
                st.buf = GlUtil.floatBuffer(arr); st.dirty = false
            }
            drawColor(identity, st.buf!!, null, GLES20.GL_POINTS, st.count, st.color, pointScale = 0.012f * pxPerMeter, round = true)
        }

        hand?.let { hd ->
            pointBuf.clear()
            for (p in hd.points) pointBuf.put(p)
            pointBuf.position(0)
            drawColor(identity, pointBuf, null, GLES20.GL_POINTS, 21, HAND, pointScale = 0.01f * pxPerMeter, round = true)
            pointBuf.clear(); pointBuf.put(hd.pinch); pointBuf.position(0)
            val pc = if (hd.pinching) colors[colorIndex] else HAND_TIP
            drawColor(identity, pointBuf, null, GLES20.GL_POINTS, 1, pc, pointScale = 0.02f * pxPerMeter, round = true)
        }

        drawPanel()

        // Reticle 1 m ahead, on top of everything.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        pointBuf.clear()
        pointBuf.put(gazeO[0] + gazeF[0]); pointBuf.put(gazeO[1] + gazeF[1]); pointBuf.put(gazeO[2] + gazeF[2])
        pointBuf.position(0)
        val rc = if (hovered >= 0) RET_HOVER else if (handVisible) RET_HAND else RET_IDLE
        drawColor(identity, pointBuf, null, GLES20.GL_POINTS, 1, rc, pointScale = 0.012f * pxPerMeter, round = true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(bTex, 0)
        quadNdc.position(0); quadUv.position(0)
        GLES20.glEnableVertexAttribArray(bPos)
        GLES20.glVertexAttribPointer(bPos, 2, GLES20.GL_FLOAT, false, 0, quadNdc)
        GLES20.glEnableVertexAttribArray(bUv)
        GLES20.glVertexAttribPointer(bUv, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bPos)
        GLES20.glDisableVertexAttribArray(bUv)
        GLES20.glDepthMask(true)
    }

    private fun drawPlanes() {
        val col = if (passthrough) PLANE_PT else PLANE_VR
        for (p in planes) {
            val poly = p.polygon ?: continue
            poly.rewind()
            val n = poly.remaining() / 2
            if (n < 3) continue
            if (planeBuf.capacity() < n * 3) planeBuf = GlUtil.floatBuffer(n * 3 + 64)
            planeBuf.clear()
            for (i in 0 until n) {
                planeBuf.put(poly.get(2 * i)); planeBuf.put(0f); planeBuf.put(poly.get(2 * i + 1))
            }
            planeBuf.position(0)
            p.centerPose.toMatrix(model, 0)
            drawColor(model, planeBuf, null, GLES20.GL_TRIANGLE_FAN, n, col)
        }
    }

    private fun drawPanel() {
        if (!panelPlaced) return
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(tTex, 0)
        unitQuad.position(0); labelUv.position(0)
        GLES20.glEnableVertexAttribArray(tPos)
        GLES20.glVertexAttribPointer(tPos, 3, GLES20.GL_FLOAT, false, 0, unitQuad)
        GLES20.glEnableVertexAttribArray(tUv)
        GLES20.glVertexAttribPointer(tUv, 2, GLES20.GL_FLOAT, false, 0, labelUv)
        for (i in 0 until 4) {
            // Columns: right * w, up * h, normal, position.
            model.fill(0f)
            model[0] = panelRight[0] * btnW; model[1] = 0f; model[2] = panelRight[2] * btnW
            model[5] = btnH
            model[8] = -panelDir[0]; model[10] = -panelDir[2]
            model[12] = panelPos[0] + panelRight[0] * btnX[i]
            model[13] = panelPos[1]
            model[14] = panelPos[2] + panelRight[2] * btnX[i]
            model[15] = 1f
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(tMVP, 1, false, mvp, 0)
            GLES20.glUniform1f(tHi, if (i == hovered) 1f else 0f)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, btnTex[i])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(tPos)
        GLES20.glDisableVertexAttribArray(tUv)
    }

    private fun drawColor(
        m: FloatArray, pos: FloatBuffer, nrm: FloatBuffer?, prim: Int, count: Int, color: FloatArray,
        lit: Boolean = false, checker: Boolean = false, pointScale: Float = 0f, round: Boolean = false
    ) {
        GLES20.glUseProgram(colProg)
        GLES20.glUniformMatrix4fv(cVP, 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(cModel, 1, false, m, 0)
        GLES20.glUniform1f(cPointScale, pointScale)
        GLES20.glUniform4fv(cColor, 1, color, 0)
        GLES20.glUniform1f(cLit, if (lit) 1f else 0f)
        GLES20.glUniform1f(cChecker, if (checker) 1f else 0f)
        GLES20.glUniform1f(cRound, if (round) 1f else 0f)
        pos.position(0)
        GLES20.glEnableVertexAttribArray(cPos)
        GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 0, pos)
        if (nrm != null) {
            nrm.position(0)
            GLES20.glEnableVertexAttribArray(cNrm)
            GLES20.glVertexAttribPointer(cNrm, 3, GLES20.GL_FLOAT, false, 0, nrm)
        } else {
            GLES20.glDisableVertexAttribArray(cNrm)
            GLES20.glVertexAttrib3f(cNrm, 0f, 1f, 0f)
        }
        GLES20.glDrawArrays(prim, 0, count)
        GLES20.glDisableVertexAttribArray(cPos)
        if (nrm != null) GLES20.glDisableVertexAttribArray(cNrm)
    }

    private fun drawDistortion(eye: Int) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(disProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(dTex, 0)
        val aspect = eyeW.toFloat() / h
        GLES20.glUniform2f(dAspect, aspect, 1f)
        GLES20.glUniform2f(dK, k1, k2)
        GLES20.glUniform1f(dScale, 1f + k1 * aspect * aspect + k2 * aspect * aspect * aspect * aspect)
        val eyeHalfMm = screenWidthMm / 4f
        val off = ((eyeHalfMm - lensSeparationMm / 2f) / eyeHalfMm).coerceIn(-0.35f, 0.35f)
        GLES20.glUniform1f(dCenter, if (eye == 0) off else -off)
        quadNdc.position(0)
        GLES20.glEnableVertexAttribArray(dPos)
        GLES20.glVertexAttribPointer(dPos, 2, GLES20.GL_FLOAT, false, 0, quadNdc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(dPos)
    }

    private fun ensureFbo(fw: Int, fh: Int) {
        if (fw == fboW && fh == fboH && fbo != 0) return
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(fboDepth), 0)
        }
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0); fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenRenderbuffers(1, t, 0); fboDepth = t[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepth)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, fw, fh)
        GLES20.glGenFramebuffers(1, t, 0); fbo = t[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, fboDepth)
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "FBO incomplete: $st")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboW = fw; fboH = fh
    }

    private fun rebuildLabels() {
        val labels = arrayOf(
            if (mode == Mode.CUBES) "Mode: Cubes" else "Mode: Paint",
            if (passthrough) "Passthrough: ON" else "Passthrough: OFF",
            "Color: " + colorNames[colorIndex],
            if (mode == Mode.CUBES) "Clear cubes" else "Clear paint"
        )
        val bg = 0xE6202830.toInt()
        for (i in 0 until 4) {
            GlUtil.deleteTexture(btnTex[i])
            val fg = if (i == 2) argb(colors[colorIndex]) else 0xFFFFFFFF.toInt()
            btnTex[i] = GlUtil.textTexture(labels[i], bg, fg)
        }
        labelsDirty = false
    }

    private fun argb(c: FloatArray): Int =
        (0xff shl 24) or ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()

    private fun buildCube() {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>()
        // normal, tangent a, tangent b
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f)
        )
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f),
            floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        for (f in faces) for (c in corners) {
            for (k in 0..2) {
                pos.add(0.5f * (f[k] + c[0] * f[3 + k] + c[1] * f[6 + k]))
                nrm.add(f[k])
            }
        }
        cubePos = GlUtil.floatBuffer(pos.toFloatArray())
        cubeNrm = GlUtil.floatBuffer(nrm.toFloatArray())
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    companion object {
        private const val TAG = "VRClaude"
        private val FLOOR = floatArrayOf(0.85f, 0.87f, 0.9f, 1f)
        private val PLANE_PT = floatArrayOf(0.6f, 0.85f, 1f, 0.18f)
        private val PLANE_VR = floatArrayOf(0.3f, 0.6f, 1f, 0.25f)
        private val GRAB = floatArrayOf(0.2f, 1f, 1f, 1f)
        private val GRAB_HINT = floatArrayOf(0.55f, 0.95f, 1f, 1f)
        private val HAND = floatArrayOf(1f, 1f, 1f, 0.9f)
        private val HAND_TIP = floatArrayOf(0.6f, 0.85f, 1f, 1f)
        private val RET_IDLE = floatArrayOf(1f, 1f, 1f, 0.95f)
        private val RET_HAND = floatArrayOf(0.55f, 0.85f, 1f, 1f)
        private val RET_HOVER = floatArrayOf(0.4f, 1f, 0.5f, 1f)
    }
}
