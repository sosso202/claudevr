package com.lyes.vrclaude

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.cardboard.sdk.DistortionRenderer
import com.google.cardboard.sdk.HeadTracker
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeTextureDescription
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Head rotation comes from the Cardboard SDK's gyro tracker every frame (smooth, low
 * latency, keeps working when ARCore loses tracking). ARCore supplies position, the
 * camera image and surfaces, and slowly corrects the gyro's yaw drift. Lens distortion
 * is the Cardboard SDK's, from the viewer's QR profile.
 */
class VrRenderer(private val act: MainActivity, private val headTracker: HeadTracker?) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    val hands = HandTracker(act)

    private val taps = AtomicInteger(0)
    @Volatile private var stereoRequest = true
    @Volatile private var recenterRequest = false
    @Volatile private var viewerDirty = true
    private var stereo = true

    fun tap() { taps.incrementAndGet() }
    fun toggleStereo() { stereoRequest = !stereoRequest }
    fun recenter() { recenterRequest = true }
    fun reloadViewer() { viewerDirty = true }

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

    // Panel: 6 buttons in a 2x3 grid, in world space.
    private var panelPlaced = false
    private val panelPos = FloatArray(3)
    private val panelDir = FloatArray(3)   // horizontal, from user to panel
    private val panelRight = FloatArray(3)
    private var hovered = -1
    private val btnW = 0.28f
    private val btnH = 0.095f
    private val btnX = floatArrayOf(-0.15f, 0.15f, -0.15f, 0.15f, -0.15f, 0.15f)
    private val btnY = floatArrayOf(0.11f, 0.11f, 0f, 0f, -0.11f, -0.11f)
    private val btnCount = 6
    private val btnTex = IntArray(btnCount)
    private var labelsDirty = true
    private var awaySince = 0L
    private var hoverStart = 0L
    private var dwellDone = false
    private var dwell = 0f

    private val gazeO = FloatArray(3)
    private val gazeF = FloatArray(3)
    private var floorY = -1.4f
    private var planes: List<Plane> = emptyList()

    // ---------------- head pose fusion ----------------
    private val qNow = FloatArray(4)       // gyro view rotation, predicted for display
    private val qFrame = FloatArray(4)     // gyro view rotation at the camera frame's time
    private val qOff = floatArrayOf(0f, 0f, 0f, 1f) // gyro world -> ARCore world correction
    private var haveOff = false
    private val headPos = FloatArray(3)
    private val headView = FloatArray(16)
    private val camToWorld = FloatArray(16)
    private val arView = FloatArray(16)
    private val tmpPos = FloatArray(3)
    private var tracking = false
    private var everTracked = false
    private var lastTracked = 0L

    // ---------------- Cardboard ----------------
    private var lens: LensDistortion? = null
    private var distortion: DistortionRenderer? = null
    private val eyeFromHead = Array(2) { FloatArray(16) }
    private val eyeProj = Array(2) { FloatArray(16) }
    private val leftEye = EyeTextureDescription()
    private val rightEye = EyeTextureDescription()

    // ---------------- GL state ----------------
    private var bgProg = 0; private var colProg = 0; private var texProg = 0
    private var camTex = 0
    private var boundSession: Session? = null
    private var geomDirty = true
    private var w = 1; private var h = 1
    private var fbo = 0; private var fboTex = 0; private var fboDepth = 0; private var fboW = 0; private var fboH = 0

    private val quadNdc = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUv = GlUtil.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val bgPos = GlUtil.floatBuffer(12)
    private var bgReady = false
    private val camProj = FloatArray(16)
    private val unitQuad = GlUtil.floatBuffer(floatArrayOf(-.5f, -.5f, 0f, .5f, -.5f, 0f, -.5f, .5f, 0f, .5f, .5f, 0f))
    private val labelUv = GlUtil.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val floorQuad = GlUtil.floatBuffer(floatArrayOf(-30f, 0f, -30f, 30f, 0f, -30f, -30f, 0f, 30f, 30f, 0f, 30f))
    private lateinit var cubePos: FloatBuffer
    private lateinit var cubeNrm: FloatBuffer
    private val pointBuf = GlUtil.floatBuffer(3 * 64)
    private var planeBuf = GlUtil.floatBuffer(3 * 256)

    private var cVP = 0; private var cModel = 0; private var cPointScale = 0; private var cPos = 0; private var cNrm = 0
    private var cColor = 0; private var cLit = 0; private var cChecker = 0; private var cRound = 0
    private var tMVP = 0; private var tPos = 0; private var tUv = 0; private var tTex = 0; private var tHi = 0
    private var bVP = 0; private var bPos = 0; private var bUv = 0; private var bTex = 0

    private val proj = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var lastTime = 0L
    private var lastStatus = ""

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
        bVP = GLES20.glGetUniformLocation(bgProg, "uVP")
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

        buildCube()
        fboW = 0; fboH = 0; fbo = 0
        for (i in 0 until btnCount) btnTex[i] = 0
        labelsDirty = true
        for (s in strokes) s.dirty = true

        // Cardboard's distortion renderer owns GL objects: (re)create with the context.
        try {
            distortion?.close()
            distortion = DistortionRenderer()
        } catch (e: Throwable) {
            Log.e(TAG, "DistortionRenderer failed", e)
            distortion = null
        }
        viewerDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        geomDirty = true
        viewerDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (stereo != stereoRequest) { stereo = stereoRequest; labelsDirty = true }
        if (labelsDirty) rebuildLabels()
        if (viewerDirty) setupViewer()

        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTime == 0L) 0.016f else ((now - lastTime) / 1000f).coerceIn(0.001f, 0.05f)
        lastTime = now

        // Gyro rotation for this displayed frame (Cardboard predicts 50 ms ahead).
        val ht = headTracker
        val orient = act.cardboardOrientation()
        if (ht != null) ht.getPose(PREDICTION_NS, orient, tmpPos, qNow) else { qNow[0] = 0f; qNow[1] = 0f; qNow[2] = 0f; qNow[3] = 1f }

        var frame: Frame? = null
        val s = session
        if (s != null) {
            if (boundSession !== s) {
                s.setCameraTextureName(camTex)
                boundSession = s
                geomDirty = true
            }
            if (geomDirty) {
                // Display geometry with the camera's own aspect, so ARCore doesn't crop it.
                val ts = s.cameraConfig.textureSize
                s.setDisplayGeometry(act.displayRotation(), ts.width, ts.height)
                geomDirty = false
            }
            frame = try { s.update() } catch (e: Exception) { Log.w(TAG, "update failed", e); null }
        }

        tracking = false
        if (frame != null && frame.timestamp != 0L) {
            if (frame.hasDisplayGeometryChanged()) {
                quadNdc.position(0); quadUv.position(0)
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc,
                    Coordinates2d.TEXTURE_NORMALIZED, quadUv
                )
                quadNdc.position(0); quadUv.position(0)
            }
            val cam = frame.camera
            cam.getProjectionMatrix(camProj, 0, 0.05f, 100f)
            tracking = cam.trackingState == TrackingState.TRACKING
            if (tracking) {
                if (everTracked && now - lastTracked > 1000) recenterRequest = true // world may have shifted
                everTracked = true
                lastTracked = now
                val pose = cam.displayOrientedPose
                headPos[0] = pose.tx(); headPos[1] = pose.ty(); headPos[2] = pose.tz()
                pose.toMatrix(camToWorld, 0)
                cam.getViewMatrix(arView, 0)
                // Gyro rotation at the moment the camera image was taken.
                if (ht != null) {
                    var delta = frame.timestamp - SystemClock.elapsedRealtimeNanos()
                    if (delta < -200_000_000L || delta > 0L) delta = -40_000_000L
                    ht.getPose(delta, orient, tmpPos, qFrame)
                    val qAr = quatFromMatrix(arView)
                    // qOff maps ARCore world into gyro world: qAr = qFrame * qOff.
                    val qMeas = quatMul(quatConj(qFrame), qAr)
                    if (!haveOff) { System.arraycopy(qMeas, 0, qOff, 0, 4); haveOff = true }
                    else quatNlerp(qOff, qMeas, 0.04f)
                }
                hands.submit(frame, cam)
            }
        }

        // Fused head view: gyro rotation (now) * correction, ARCore position.
        val qView = if (ht != null) quatMul(qNow, qOff) else if (tracking) quatFromMatrix(arView) else qNow
        quatToMatrix(qView, headView)
        Matrix.translateM(headView, 0, -headPos[0], -headPos[1], -headPos[2])
        gazeO[0] = headPos[0]; gazeO[1] = headPos[1]; gazeO[2] = headPos[2]
        gazeF[0] = -headView[2]; gazeF[1] = -headView[6]; gazeF[2] = -headView[10]
        // Without ARCore tracking, keep the camera picture locked to the head.
        if (!tracking) Matrix.invertM(camToWorld, 0, headView, 0)
        updateBackgroundQuad(frame != null)

        update(frame, s, dt, now)
        updateStatus(tracking || (everTracked && now - lastTracked < 2000), frame != null)

        if (stereo && lens != null && distortion != null) {
            ensureFbo(w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            for (eye in 0..1) {
                GLES20.glViewport(eye * w / 2, 0, w / 2, h)
                Matrix.multiplyMM(eyeView, 0, eyeFromHead[eye], 0, headView, 0)
                System.arraycopy(eyeProj[eye], 0, proj, 0, 16)
                drawScene()
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            leftEye.texture = fboTex.toLong()
            rightEye.texture = fboTex.toLong()
            distortion!!.renderEyeToDisplay(0L, 0, 0, w, h, leftEye, rightEye)
        } else {
            GLES20.glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 65f, w.toFloat() / h, 0.05f, 100f)
            System.arraycopy(headView, 0, eyeView, 0, 16)
            drawScene()
        }
    }

    private fun setupViewer() {
        viewerDirty = false
        if (w <= 1 || h <= 1 || distortion == null) return
        try {
            val params = QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams()
            lens?.close()
            val l = LensDistortion(params, w, h)
            lens = l
            for (eye in 0..1) {
                l.getEyeFromHeadMatrix(eye, eyeFromHead[eye])
                l.getEyeProjectionMatrix(eye, 0.05f, 100f, eyeProj[eye])
                distortion?.setMesh(l.getDistortionMesh(eye), eye)
            }
            leftEye.leftU = 0f; leftEye.rightU = 0.5f; leftEye.topV = 1f; leftEye.bottomV = 0f
            rightEye.leftU = 0.5f; rightEye.rightU = 1f; rightEye.topV = 1f; rightEye.bottomV = 0f
        } catch (e: Throwable) {
            Log.e(TAG, "Viewer setup failed", e)
            lens = null
            act.setStatus("Viewer setup failed: ${e.message}")
        }
    }

    /** Camera image corners, 2 m out along the camera's view when the image was taken. */
    private fun updateBackgroundQuad(haveFrame: Boolean) {
        bgReady = haveFrame
        if (!haveFrame) return
        val d = 2f
        val c = FloatArray(4); val wv = FloatArray(4)
        bgPos.clear()
        for (i in 0 until 4) {
            val nx = quadNdc.get(2 * i); val ny = quadNdc.get(2 * i + 1)
            c[0] = (nx + camProj[8]) / camProj[0] * d
            c[1] = (ny + camProj[9]) / camProj[5] * d
            c[2] = -d; c[3] = 1f
            Matrix.multiplyMV(wv, 0, camToWorld, 0, c, 0)
            bgPos.put(wv[0]); bgPos.put(wv[1]); bgPos.put(wv[2])
        }
        bgPos.position(0)
    }

    // ================= logic =================

    private fun update(frame: Frame?, s: Session?, dt: Float, now: Long) {
        // Planes and floor.
        if (s != null && tracking) {
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
        }

        // Panel stays put; moves when out of view for 1.5 s, on recenter, or after a tracking drop.
        val away = horizontalAngle(gazeF, panelDir) > 75f
        if (!away) awaySince = 0L else if (awaySince == 0L) awaySince = now
        if (recenterRequest || !panelPlaced || (awaySince != 0L && now - awaySince > 1500)) {
            placePanel(); recenterRequest = false; awaySince = 0L
        }
        val hv = gazeButton()
        if (hv != hovered) { hovered = hv; hoverStart = now; dwellDone = false }
        // Dwell click: look at a button for 1 s.
        dwell = if (hovered >= 0 && !dwellDone) ((now - hoverStart) / DWELL_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (hovered >= 0 && !dwellDone && now - hoverStart >= DWELL_MS) pressButton(hovered)

        // Hands.
        val hd = hands.latest
        handVisible = hd != null && now - hd.time < 350
        hand = if (handVisible) hd else null
        val pinchNow = hand?.pinching == true
        hand?.let { recordHistory(it.pinch, now) }
        if (pinchNow && !pinchPrev) onPinchStart(frame, hand!!)
        if (pinchNow) onPinchHold(hand!!)
        if (!pinchNow && pinchPrev) onPinchEnd()
        pinchPrev = pinchNow

        val n = taps.getAndSet(0)
        repeat(n) { onTap(frame) }

        physics(dt)
    }

    private fun recordHistory(p: FloatArray, now: Long) {
        history.addLast(Pair(now, p.copyOf()))
        while (history.isNotEmpty() && now - history.first().first > 150) history.removeFirst()
    }

    private fun onPinchStart(frame: Frame?, hd: HandTracker.Hand) {
        if (hovered >= 0) { pressButton(hovered); return }
        if (mode == Mode.PAINT) {
            val st = Stroke(colors[colorIndex].copyOf())
            st.pts.add(hd.pinch[0]); st.pts.add(hd.pinch[1]); st.pts.add(hd.pinch[2])
            strokes.add(st); activeStroke = st
            return
        }
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

    private fun onPinchEnd() {
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

    private fun onTap(frame: Frame?) {
        if (hovered >= 0) { pressButton(hovered); return }
        if (mode == Mode.CUBES) placeCube(frame)
    }

    private fun pressButton(i: Int) {
        dwellDone = true
        when (i) {
            0 -> mode = if (mode == Mode.CUBES) Mode.PAINT else Mode.CUBES
            1 -> passthrough = !passthrough
            2 -> colorIndex = (colorIndex + 1) % colors.size
            3 -> if (mode == Mode.CUBES) { cubes.clear(); held = null } else { strokes.clear(); activeStroke = null }
            4 -> stereoRequest = !stereoRequest
            5 -> act.scanViewer()
        }
        labelsDirty = true
    }

    private fun placeCube(frame: Frame?) {
        val size = 0.1f
        val color = colors[cubeColorIndex % colors.size].copyOf()
        cubeColorIndex++
        if (frame != null && tracking) {
            try {
                for (hit in frame.hitTest(gazeO, 0, gazeF, 0)) {
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
        }
        cubes.add(Cube(floatArrayOf(gazeO[0] + gazeF[0] * 0.6f, gazeO[1] + gazeF[1] * 0.6f, gazeO[2] + gazeF[2] * 0.6f), size, color))
        trimCubes()
    }

    private fun trimCubes() { while (cubes.size > 40) cubes.removeAt(0) }

    private fun supportHeight(c: Cube): Float {
        var best = floorY
        for (p in planes) {
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING || p.trackingState != TrackingState.TRACKING) continue
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
                if (a === held) { b.vel[0] += dx * push * 20f; b.vel[1] += dy * push * 20f; b.vel[2] += dz * push * 20f }
                if (b === held) { a.vel[0] -= dx * push * 20f; a.vel[1] -= dy * push * 20f; a.vel[2] -= dz * push * 20f }
            }
        }
    }

    private fun placePanel() {
        var fx = gazeF[0]; var fz = gazeF[2]
        val l = sqrt(fx * fx + fz * fz)
        if (l < 1e-3f) { fx = 0f; fz = -1f } else { fx /= l; fz /= l }
        panelDir[0] = fx; panelDir[1] = 0f; panelDir[2] = fz
        panelRight[0] = -fz; panelRight[1] = 0f; panelRight[2] = fx
        panelPos[0] = gazeO[0] + fx * 0.8f
        panelPos[1] = gazeO[1] - 0.1f
        panelPos[2] = gazeO[2] + fz * 0.8f
        panelPlaced = true
    }

    private fun horizontalAngle(a: FloatArray, b: FloatArray): Float {
        val la = sqrt(a[0] * a[0] + a[2] * a[2]); val lb = sqrt(b[0] * b[0] + b[2] * b[2])
        if (la < 1e-3f || lb < 1e-3f) return 0f
        val c = ((a[0] * b[0] + a[2] * b[2]) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }

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
        for (i in 0 until btnCount) {
            if (abs(lx - btnX[i]) <= btnW * 0.5f && abs(hy - btnY[i]) <= btnH * 0.5f) return i
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

    private fun updateStatus(trackingOk: Boolean, haveCamera: Boolean) {
        val msg = when {
            !haveCamera -> ""
            !trackingOk -> "Look at the floor and move slowly to restart room tracking."
            hands.error != null && !hands.ready -> "Hand tracking failed: ${hands.error}. Tap the screen to click."
            !stereo -> "Phone view. Hold the screen for viewer view."
            else -> ""
        }
        if (msg != lastStatus) { lastStatus = msg; act.setStatus(msg) }
    }

    // ================= drawing =================

    private fun drawScene() {
        Matrix.multiplyMM(vp, 0, proj, 0, eyeView, 0)
        val viewportH = h.toFloat()

        if (!passthrough) {
            GLES20.glClearColor(0.04f, 0.05f, 0.09f, 1f)
        }
        if (passthrough && bgReady) drawBackground()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        if (!passthrough) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, floorY, 0f)
            drawColor(model, floorQuad, null, GLES20.GL_TRIANGLE_STRIP, 4, FLOOR, checker = true)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (tracking) {
            GLES20.glDepthMask(false)
            drawPlanes()
            GLES20.glDepthMask(true)
        }

        for (c in cubes) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, c.pos[0], c.pos[1], c.pos[2])
            Matrix.rotateM(model, 0, Math.toDegrees(c.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(model, 0, c.size, c.size, c.size)
            val col = if (c === held) GRAB else if (hand != null && dist(c.pos, hand!!.pinch) < 0.15f && mode == Mode.CUBES) GRAB_HINT else c.color
            drawColor(model, cubePos, cubeNrm, GLES20.GL_TRIANGLES, 36, col, lit = true)
        }

        val pxPerMeter = proj[5] * viewportH / 2f
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

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        pointBuf.clear()
        pointBuf.put(gazeO[0] + gazeF[0]); pointBuf.put(gazeO[1] + gazeF[1]); pointBuf.put(gazeO[2] + gazeF[2])
        pointBuf.position(0)
        val rc = if (hovered >= 0) RET_HOVER else if (handVisible) RET_HAND else RET_IDLE
        drawColor(identity, pointBuf, null, GLES20.GL_POINTS, 1, rc, pointScale = (0.012f + 0.03f * dwell) * pxPerMeter, round = true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glUniformMatrix4fv(bVP, 1, false, vp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(bTex, 0)
        bgPos.position(0); quadUv.position(0)
        GLES20.glEnableVertexAttribArray(bPos)
        GLES20.glVertexAttribPointer(bPos, 3, GLES20.GL_FLOAT, false, 0, bgPos)
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
        for (i in 0 until btnCount) {
            model.fill(0f)
            model[0] = panelRight[0] * btnW; model[2] = panelRight[2] * btnW
            model[5] = btnH
            model[8] = -panelDir[0]; model[10] = -panelDir[2]
            model[12] = panelPos[0] + panelRight[0] * btnX[i]
            model[13] = panelPos[1] + btnY[i]
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
            if (mode == Mode.CUBES) "Clear cubes" else "Clear paint",
            if (stereoRequest) "Phone view" else "Viewer view",
            "Scan viewer QR"
        )
        val bg = 0xE6202830.toInt()
        for (i in 0 until btnCount) {
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

    // ---------------- quaternions (x, y, z, w) ----------------

    private fun quatMul(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    )

    private fun quatConj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** a = normalize(a + (b - a) * t), along the shorter way round. */
    private fun quatNlerp(a: FloatArray, b: FloatArray, t: Float) {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val s = if (dot < 0f) -1f else 1f
        for (i in 0..3) a[i] += (b[i] * s - a[i]) * t
        val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3])
        if (l > 1e-6f) for (i in 0..3) a[i] /= l
    }

    /** Rotation matrix (column-major, like Cardboard's Quatf::ToMatrix). */
    private fun quatToMatrix(q: FloatArray, m: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = 2 * x * x; val yy = 2 * y * y; val zz = 2 * z * z
        val xy = 2 * x * y; val xz = 2 * x * z; val yz = 2 * y * z
        val xw = 2 * x * w; val yw = 2 * y * w; val zw = 2 * z * w
        m[0] = 1 - yy - zz; m[1] = xy + zw; m[2] = xz - yw; m[3] = 0f
        m[4] = xy - zw; m[5] = 1 - xx - zz; m[6] = yz + xw; m[7] = 0f
        m[8] = xz + yw; m[9] = yz - xw; m[10] = 1 - xx - yy; m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    /** Quaternion of a column-major matrix's rotation part. */
    private fun quatFromMatrix(m: FloatArray): FloatArray {
        // r(row, col) = m[col * 4 + row]
        val r00 = m[0]; val r10 = m[1]; val r20 = m[2]
        val r01 = m[4]; val r11 = m[5]; val r21 = m[6]
        val r02 = m[8]; val r12 = m[9]; val r22 = m[10]
        val tr = r00 + r11 + r22
        val q = FloatArray(4)
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (r21 - r12) / s; q[1] = (r02 - r20) / s; q[2] = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            q[3] = (r21 - r12) / s; q[0] = 0.25f * s; q[1] = (r01 + r10) / s; q[2] = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            q[3] = (r02 - r20) / s; q[0] = (r01 + r10) / s; q[1] = 0.25f * s; q[2] = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            q[3] = (r10 - r01) / s; q[0] = (r02 + r20) / s; q[1] = (r12 + r21) / s; q[2] = 0.25f * s
        }
        return q
    }

    companion object {
        private const val TAG = "VRClaude"
        private const val DWELL_MS = 1000L
        private const val PREDICTION_NS = 50_000_000L
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
