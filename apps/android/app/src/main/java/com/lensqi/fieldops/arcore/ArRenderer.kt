package com.lensqi.fieldops.arcore

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
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
 * Runs live assistance on ARCore's camera. The GL thread updates the session, draws the camera, samples a frame every
 * 100 ms and creates anchors; tracking, quality checks and uploads run on a worker so rendering never waits on them.
 */
class ArLiveRenderer(
    private val session: Session,
    private val depthApi: Boolean,
    private val live: LiveCameraSession,
    private val displayRotation: () -> Int,
) : GLSurfaceView.Renderer {
    private class Capture(val view: ArCameraView, val depth: DepthSamples, val time: Long)

    private val background = CameraBackground()
    private val anchors = SpatialAnchors()
    private val captures = LinkedHashMap<String, Capture>()
    private val detections = ConcurrentLinkedQueue<Pair<String, List<LiveObject>>>()
    private val worker = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    @Volatile private var closed = false
    private var width = 0; private var height = 0; private var lastSample = 0L

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
        val rotation = frame.imageRotation()
        val map = frame.viewMap(rotation)
        val view = frame.cameraView(map)
        while (true) {
            val (id, objects) = detections.poll() ?: break
            val capture = captures[id] ?: continue
            anchors.seed(capture.view, capture.depth, objects, capture.time) { session.anchorAt(it) }
        }
        if (now - lastSample < 100 || working.get()) return
        val yuv = runCatching { frame.acquireCameraImage().use { it.copyYuv() } }.getOrNull() ?: return
        lastSample = now
        val frameId = live.nextFrameId()
        // Only a frame that may be uploaded for detection needs its camera and depth kept for anchoring later.
        val upload = live.uploadDue(now)
        if (upload && view != null) {
            captures[frameId] = Capture(view, frame.depthSamples(view, rotation, width, height, depthApi), now)
            captures.entries.removeAll { now - it.value.time > 25_000 }
        }
        val spatial = SpatialFrame(
            toView = map::box,
            anchored = view?.let { anchors.visible(it, now) }.orEmpty(),
            offscreen = view?.let { anchors.offscreen(it, now) } ?: 0,
            tracking = frame.trackingLabel(depthApi),
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

    /** Call after the GL thread has paused. */
    fun close() {
        closed = true
        worker.shutdownNow()
        anchors.clear()
    }
}
