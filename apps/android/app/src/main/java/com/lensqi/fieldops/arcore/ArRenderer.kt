package com.lensqi.fieldops.arcore

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.lensqi.fieldops.domain.live.GrayFrame
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.ui.camera.LiveCameraSession
import com.lensqi.fieldops.ui.camera.SpatialFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Draws ARCore's camera texture across the view, cropped exactly as ARCore maps it. */
private class CameraBackground {
    var texture = 0; private set
    private var program = 0
    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val positions = buffer(quad)
    private val texCoords = buffer(FloatArray(8))

    fun create() {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        for ((k, v) in listOf(GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE,
            GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR)) GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }"))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES u_Texture; void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }"))
            GLES20.glLinkProgram(p)
        }
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            val out = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, out)
            texCoords.position(0); texCoords.put(out); texCoords.position(0)
        }
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "a_Position"); val tex = GLES20.glGetAttribLocation(program, "a_TexCoord")
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
        GLES20.glDepthMask(true); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, source); GLES20.glCompileShader(it) }
    private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}

/**
 * What the camera screen asks of the AR renderer. Requests are queued here and applied on the GL thread that owns the
 * ARCore session, so the screen never touches the session.
 */
class SpatialControl {
    internal val floorTaps = ConcurrentLinkedQueue<Pair<Float, Float>>()
    internal val confirms = ConcurrentLinkedQueue<String>()
    internal val resets = AtomicInteger()
    internal val floorClears = AtomicInteger()

    /** The technician tapped the floor at this view pixel. */
    fun tapFloor(x: Float, y: Float) { floorTaps.add(x to y) }
    fun clearFloor() { floorClears.incrementAndGet() }
    /** The technician confirmed the part held by this anchor as the target. */
    fun confirm(anchorId: String) { confirms.add(anchorId) }
    /** A new step, task or inspection: drop every anchor, the target and any detection still in flight. */
    fun reset() { resets.incrementAndGet() }

    internal val markTaps = ConcurrentLinkedQueue<Triple<Float, Float, String>>()
    internal val checkTaps = ConcurrentLinkedQueue<Pair<Float, Float>>()
    internal val assetAnswers = ConcurrentLinkedQueue<AssetAnswer>()
    /** The equipment this camera is about; null outside an inspection with a documented asset. */
    @Volatile var subject: RegistrationSubject? = null
    /** The port layout: loaded from the phone by the screen, updated here as the technician marks ports. */
    @Volatile var layout: AssetLayout? = null

    /** The technician tapped where port [label] is, at this view pixel. */
    fun markPort(x: Float, y: Float, label: String) { markTaps.add(Triple(x, y, label)) }
    /** The technician tapped where the step's port really is, to measure the layout's error. */
    fun checkPort(x: Float, y: Float) { checkTaps.add(x to y) }
    fun answerAsset(answer: AssetAnswer) { assetAnswers.add(answer) }
}

/** The technician's answer to "is the marker on this asset?"; AskAgain undoes a No. */
enum class AssetAnswer { Yes, No, AskAgain }

/**
 * Runs live assistance on ARCore's camera. The GL thread updates the session, draws the camera, samples a frame every
 * 100 ms and creates anchors; tracking, quality checks and uploads run on a worker so rendering never waits on them.
 */
class ArLiveRenderer(
    private val session: Session,
    private val depthApi: Boolean,
    private val live: LiveCameraSession,
    private val control: SpatialControl,
    /** Whether the registration marker is in ARCore's image database. */
    private val markers: Boolean,
    private val displayRotation: () -> Int,
) : GLSurfaceView.Renderer {
    /** A frame that may be uploaded for detection: the camera and depth at that instant, and the step's part then. */
    private class Capture(val view: ArCameraView, val depth: DepthSamples, val time: Long, val preferred: String?)

    private val background = CameraBackground()
    private val anchors = SpatialAnchors()
    private val captures = LinkedHashMap<String, Capture>()
    private val detections = ConcurrentLinkedQueue<Pair<String, List<LiveObject>>>()
    private val worker = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    @Volatile private var closed = false
    private var width = 0; private var height = 0; private var lastSample = 0L
    private val floor = FloorReference()
    private var floorNote: Pair<String, Long>? = null
    private var resets = 0; private var floorClears = 0
    private var renderTicks = 0; private var renderAt = 0L; private var renderFps = 0
    private var wasTracking: Boolean? = null
    private var registration = Registration()
    private var registrationNote: Pair<String, Long>? = null
    private var markerWas = MarkerTracking.None

    /** Accepted detections arrive from the network; anchors are created on the GL thread that owns the session. */
    fun onDetection(frameId: String, objects: List<LiveObject>) { detections.add(frameId to objects) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        session.setCameraTextureName(background.texture)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h); width = w; height = h
        session.setDisplayGeometry(displayRotation(), w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (closed) return
        val frame = runCatching { session.update() }.getOrNull() ?: return
        background.draw(frame)
        val now = SystemClock.elapsedRealtime()
        renderTicks++
        if (now - renderAt >= 1000) { renderFps = (renderTicks * 1000 / (now - renderAt).coerceAtLeast(1)).toInt(); renderTicks = 0; renderAt = now }
        val rotation = frame.imageRotation()
        val map = frame.viewMap(rotation)
        val view = frame.cameraView(map)
        if (wasTracking != (view != null)) { Log.i(TAG, "tracking ${if (view != null) "on" else "off · ${frame.camera.trackingFailureReason}"}"); wasTracking = view != null }
        floor.tracking(view != null, now)
        val subject = control.subject
        if (subject != null) {
            val (tracking, pose) = if (view != null && markers) session.marker() else MarkerTracking.None to null
            registration.update(tracking, pose)
            if (tracking != markerWas) { Log.i(TAG, "marker $tracking"); markerWas = tracking }
        }
        applyRequests(frame, view, now)
        while (true) {
            val (id, objects) = detections.poll() ?: break
            val capture = captures[id] ?: continue
            anchors.preferredConcept = capture.preferred
            val (made, dropped) = anchors.created to anchors.detached
            val held = anchors.seed(capture.view, capture.depth, objects, capture.time) { session.anchorAt(it) }
            Log.i(TAG, "detections ${objects.size} · anchored $held · created ${anchors.created - made} · detached ${anchors.detached - dropped} · " +
                "active ${anchors.size} · ${now - capture.time} ms after capture")
        }
        if (now - lastSample < 100 || working.get()) return
        val yuv = runCatching { frame.acquireCameraImage().use { it.copyYuv() } }.getOrNull() ?: return
        lastSample = now
        val frameId = live.nextFrameId()
        // Only a frame that may be uploaded for detection needs its camera and depth kept for anchoring later.
        val upload = live.uploadDue(now)
        if (upload && view != null) {
            captures[frameId] = Capture(view, frame.depthSamples(view, rotation, width, height, depthApi), now, live.targetConcept)
            captures.entries.removeAll { now - it.value.time > 25_000 }
        }
        val floorY = floor.y
        val spatial = SpatialFrame(
            toView = map::box,
            anchored = view?.let { anchors.visible(it, now, floorY) }.orEmpty(),
            offscreen = view?.let { anchors.offscreen(it, now) } ?: 0,
            tracking = frame.trackingLabel(depthApi),
            target = anchors.target(view, now),
            floor = FloorStatus(floorY != null, if (floorY != null && view != null) ((view.cameraY - floorY) * 100).toInt() else null,
                floorNote?.takeIf { now - it.second < 5000 }?.first),
            renderFps = renderFps,
            registration = subject?.let { s ->
                if (!markers) RegistrationView(PortGuidance(GuideLevel.FindMarker, "Equipment registration isn't available: the marker image couldn't be loaded into ARCore"), available = false)
                else registrationView(view, registration, s, control.layout, registrationNote?.takeIf { now - it.second < 5000 }?.first)
            },
        )
        working.set(true)
        worker.execute {
            try {
                val (pixels, w, h) = yuv.upright(rotation, 160, color = false)
                live.analyse(GrayFrame(frameId, now, w, h, pixels), spatial) {
                    val bitmap = yuv.uprightBitmap(rotation, 640)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it); bitmap.recycle() }.toByteArray()
                }
            } finally { working.set(false) }
        }
    }

    /** Applies the screen's requests. A reset also drops captures and queued detections, so no late answer lands. */
    private fun applyRequests(frame: Frame, view: ArCameraView?, now: Long) {
        control.resets.get().let { if (it != resets) { resets = it; anchors.clear(); captures.clear(); detections.clear(); registration = Registration(); registrationNote = null; Log.i(TAG, "reset") } }
        control.floorClears.get().let { if (it != floorClears) { floorClears = it; floor.clear() } }
        control.floorTaps.poll()?.let { (x, y) ->
            val found = view?.let { frame.floorAt(x, y, it.cameraY) }
            if (found != null) floor.set(found)
            floorNote = (if (found != null) "Floor set" else "No floor found there · move the phone slowly over the floor, then tap it again") to now
            Log.i(TAG, "floor tap ${if (found != null) "set" else "refused"}")
        }
        control.confirms.poll()?.let { id -> Log.i(TAG, "confirm ${if (anchors.confirm(id, now)) "held" else "anchor gone"}") }
        val subject = control.subject ?: return
        control.assetAnswers.poll()?.let { a ->
            when (a) { AssetAnswer.Yes -> registration.confirmAsset(subject.assetId); AssetAnswer.No -> registration.refuse(); AssetAnswer.AskAgain -> registration.reconsider() }
            Log.i(TAG, "asset $a")
        }
        val pose = registration.pose?.takeIf { registration.tracking != MarkerTracking.None }
        fun refusal() = when {
            registration.assetConfirmed != subject.assetId -> "Confirm the marker is on ${subject.assetName} first"
            pose == null || view == null -> "Point at the marker first"
            else -> null
        }
        control.markTaps.poll()?.let { (x, y, label) ->
            val note = refusal() ?: frame.surfaceAt(x, y)?.let { w ->
                val p = pose!!.local(w)
                val base = control.layout?.takeIf { it.fits(subject) } ?: subject.emptyLayout()
                control.layout = base.copy(fixtureRevision = subject.fixtureRevision)
                    .marked(LayoutPoint(label, p[0], p[1], p[2], "Marked by the technician on this phone", Instant.now().toString()))
                registration.layoutChanged()
                "Marked $label"
            } ?: "No surface found there · move the phone slowly so ARCore sees the panel, then tap $label again"
            registrationNote = note to now
            Log.i(TAG, "mark $label · $note")
        }
        control.checkTaps.poll()?.let { (x, y) ->
            val port = subject.port
            val point = port?.let { control.layout?.takeIf { l -> l.fits(subject) }?.point(it) }
            val note = refusal() ?: if (point == null) "Mark ${port ?: "the port"} before checking it" else frame.surfaceAt(x, y)?.let { w ->
                val off = dist(pose!!.local(w), point.position)
                registration.check(off)
                "Check ${registration.checks.size}: ${(off * 1000).roundToInt()} mm from the mark"
            } ?: "No surface found there · move the phone slowly so ARCore sees the panel, then tap $port again"
            registrationNote = note to now
            Log.i(TAG, "check · $note")
        }
    }

    /** Call after the GL thread has paused. */
    fun close() {
        closed = true
        worker.shutdownNow()
        anchors.clear()
    }

    private companion object { const val TAG = "LensQiAR" }
}
