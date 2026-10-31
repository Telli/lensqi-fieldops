package com.lensqi.fieldops.arcore

import kotlinx.serialization.Serializable
import kotlin.math.acos
import kotlin.math.roundToInt

/*
 * Registered equipment (phase 4 of docs/SPATIAL_AR_PLAN.md). A printed marker of known width, tracked by ARCore as an
 * Augmented Image, gives the equipment a pose with orientation. Port positions are stored in that marker's frame, as the
 * technician marked them on the phone; nothing here is authored from a guess or from an AI detection. A port gets an
 * arrow only after the technician has shown, by tapping the real port, that the error is under half the port spacing.
 */

/** The printed registration marker: its id in ARCore's image database, its asset file, and its printed width. */
object RegistrationMarker {
    const val ID = "lensqi-marker-v1"
    const val ASSET = "registration/lensqi-marker-v1.png"
    /** Width of the printed square in metres; docs/registration/README.md says how to print and measure it. */
    const val WIDTH_M = 0.08
}

/**
 * A rigid pose as ARCore's Pose.toMatrix returns it (column-major 4×4). For the marker, local x runs right along the
 * marker, y out of its face and z down the marker, in metres.
 */
class RigidPose(private val m: DoubleArray) {
    /** World point for a point in this pose's frame. */
    fun apply(p: DoubleArray) = doubleArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )

    /** This pose's frame coordinates of a world point (Rᵀ(w − t)). */
    fun local(w: DoubleArray): DoubleArray {
        val dx = w[0] - m[12]; val dy = w[1] - m[13]; val dz = w[2] - m[14]
        return doubleArrayOf(m[0] * dx + m[1] * dy + m[2] * dz, m[4] * dx + m[5] * dy + m[6] * dz, m[8] * dx + m[9] * dy + m[10] * dz)
    }

    val origin get() = doubleArrayOf(m[12], m[13], m[14])

    /** How far another pose's origin is from this one, in metres. */
    fun shift(o: RigidPose) = dist(origin, o.origin)

    /** The rotation between the two poses, in degrees. */
    fun turn(o: RigidPose): Double {
        var trace = 0.0
        for (c in 0 until 3) for (r in 0 until 3) trace += m[c * 4 + r] * o.m[c * 4 + r]
        return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
    }

    companion object { fun of(f: FloatArray) = RigidPose(DoubleArray(16) { f[it].toDouble() }) }
}

/** One port position in the marker's frame, with where it came from. */
@Serializable
data class LayoutPoint(val label: String, val x: Double, val y: Double, val z: Double, val source: String, val markedAt: String) {
    val position get() = doubleArrayOf(x, y, z)
}

/**
 * Port positions for one equipment instance, in its marker's frame. Kept apart from AI detections, and tied to the
 * fixture, the asset, its model (the fixture's asset kind) and the fixture revision it was marked under.
 */
@Serializable
data class AssetLayout(
    val fixtureId: String,
    val fixtureRevision: Int,
    val assetId: String,
    val model: String,
    val markerId: String = RegistrationMarker.ID,
    val markerWidthM: Double = RegistrationMarker.WIDTH_M,
    val frame: String = "marker: x right along the marker, y out of its face, z down the marker; metres",
    val points: List<LayoutPoint> = emptyList(),
) {
    fun point(label: String) = points.lastOrNull { it.label == label }

    /** Adds or replaces the mark for [label]. */
    fun marked(p: LayoutPoint) = copy(points = points.filter { it.label != p.label } + p)

    /** Whether this layout belongs to [s]: same fixture, asset and model, and the same marker. */
    fun fits(s: RegistrationSubject) = fixtureId == s.fixtureId && assetId == s.assetId && model == s.model && markerId == RegistrationMarker.ID
}

/** The equipment the camera is about and the port the step needs, from the inspection's fixture and connection record. */
data class RegistrationSubject(
    val fixtureId: String,
    val fixtureRevision: Int,
    val assetId: String,
    val assetName: String,
    val model: String,
    /** The port the documented connection uses (documentation of intent, not proof of what is plugged in). */
    val port: String?,
) {
    /** The ports the technician may mark: the step's port and its neighbours, which give the port spacing. */
    val markable get() = port?.let { neighbourLabels(it).let { n -> listOfNotNull(n.first, it, n.second) } }.orEmpty()

    fun emptyLayout() = AssetLayout(fixtureId, fixtureRevision, assetId, model)
}

/** The labels either side of a numbered port ("P2" → "P1", "P3"); null where there is none. */
fun neighbourLabels(label: String): Pair<String?, String?> {
    val m = Regex("^(.*?)(\\d+)$").find(label) ?: return null to null
    val prefix = m.groupValues[1]; val n = m.groupValues[2].toInt()
    return (if (n > 0) "$prefix${n - 1}" else null) to "$prefix${n + 1}"
}

/** Distance from [port] to its nearest marked neighbour, in metres; null until a neighbour is marked. */
fun portPitch(layout: AssetLayout, port: String): Double? {
    val at = layout.point(port)?.position ?: return null
    val (a, b) = neighbourLabels(port)
    return listOfNotNull(a, b).mapNotNull { layout.point(it)?.position }.map { dist(at, it) }.filter { it > 0.003 }.minOrNull()
}

/** How ARCore is following the marker: in view, at its last seen position (out of view), or not at all. */
enum class MarkerTracking { None, Full, LastKnown }

/**
 * One AR session's registration: which asset the technician says the marker is on, and the accuracy they have shown by
 * tapping the real port. A new session starts unconfirmed. When the marker moves by more than [movedM] or [movedDeg]
 * from where the checks were made (the equipment or the sticker moved, or ARCore corrected), or tracking is lost,
 * the checks no longer count and must be made again.
 */
class Registration(private val movedM: Double = 0.02, private val movedDeg: Double = 5.0, val checksNeeded: Int = 2) {
    var assetConfirmed: String? = null; private set
    var refused = false; private set
    var tracking = MarkerTracking.None; private set
    var pose: RigidPose? = null; private set
    private var checkedAt: RigidPose? = null
    private val residuals = mutableListOf<Double>()
    val checks: List<Double> get() = residuals
    /** Why the checks were dropped, until the next check. */
    var revalidate: String? = null; private set

    fun update(tracking: MarkerTracking, pose: RigidPose?) {
        this.tracking = tracking
        if (tracking == MarkerTracking.None || pose == null) {
            if (residuals.isNotEmpty()) drop("Lost the marker · check the port position again")
            return
        }
        this.pose = pose
        val at = checkedAt
        if (at != null && residuals.isNotEmpty() && (at.shift(pose) > movedM || at.turn(pose) > movedDeg)) drop("The marker moved · check the port position again")
    }

    fun confirmAsset(assetId: String) { assetConfirmed = assetId; refused = false }
    fun refuse() { assetConfirmed = null; refused = true; drop(null) }
    /** Undoes a refusal, so the screen asks again. */
    fun reconsider() { refused = false }

    /** A check: the technician tapped where the port really is, [residualM] from where the layout puts it. */
    fun check(residualM: Double) {
        if (residuals.isEmpty()) checkedAt = pose
        residuals += residualM
        revalidate = null
    }

    /** A new or moved mark: earlier checks were against the old position. */
    fun layoutChanged() = drop(null)

    private fun drop(reason: String?) { residuals.clear(); checkedAt = null; revalidate = reason }
}

enum class GuideLevel { FindMarker, ConfirmAsset, Refused, Equipment, Port }

/** What the screen shows about the registered equipment and the step's port. Only [GuideLevel.Port] points at a port. */
data class PortGuidance(val level: GuideLevel, val text: String, val worstErrorMm: Int? = null, val pitchMm: Int? = null)

/**
 * The guidance for [subject] given the registration and the layout. Port-level guidance needs a confirmed asset, a
 * tracked marker, a layout for this model with the port and a neighbour marked, and [Registration.checksNeeded] checks
 * all under half the port spacing. Anything less points at the equipment and asks the technician to find the port.
 */
fun portGuidance(subject: RegistrationSubject, reg: Registration, layout: AssetLayout?): PortGuidance {
    val name = subject.assetName
    if (reg.refused) return PortGuidance(GuideLevel.Refused, "Not registered · the marker isn't on $name. Move it, or open the right asset's inspection")
    if (reg.tracking == MarkerTracking.None) return PortGuidance(GuideLevel.FindMarker, "Point at the LensQi marker on $name to register it")
    if (reg.assetConfirmed != subject.assetId) return PortGuidance(GuideLevel.ConfirmAsset, "Marker found · is it on $name?")
    val port = subject.port ?: return PortGuidance(GuideLevel.Equipment, "$name registered · no port is documented for this step")
    val usable = layout?.takeIf { it.fits(subject) }
    if (usable?.point(port) == null) return PortGuidance(GuideLevel.Equipment, "$port is on $name · not marked yet. Tap Mark ports, then tap $port")
    val pitch = portPitch(usable, port)
        ?: return PortGuidance(GuideLevel.Equipment, "$port is on $name · mark a port next to it so LensQi knows how close the ports are")
    val pitchMm = (pitch * 1000).roundToInt()
    reg.revalidate?.let { return PortGuidance(GuideLevel.Equipment, "$port is on $name · $it", pitchMm = pitchMm) }
    val worst = reg.checks.maxOrNull()
    val worstMm = worst?.let { (it * 1000).roundToInt() }
    if (worst != null && worst >= pitch / 2)
        return PortGuidance(GuideLevel.Equipment, "$port is on $name · a check was $worstMm mm off and the ports are $pitchMm mm apart, so LensQi won't point at a port. Find $port by its label, or mark it again", worstMm, pitchMm)
    if (reg.checks.size < reg.checksNeeded) {
        val left = reg.checksNeeded - reg.checks.size
        return PortGuidance(GuideLevel.Equipment, "$port is on $name · tap Check, then tap the real $port ($left more time${if (left == 1) "" else "s"}) before LensQi points at it", worstMm, pitchMm)
    }
    val held = if (reg.tracking == MarkerTracking.LastKnown) " · marker out of view, using where it was last seen" else ""
    return PortGuidance(GuideLevel.Port, "Port $port · checked to within $worstMm mm, ports $pitchMm mm apart$held", worstMm, pitchMm)
}

/** A point on screen, in view units. */
data class ViewPoint(val x: Double, val y: Double)

/**
 * What the screen draws for the registered equipment in one frame. [portAt] and [portCue] are set only at
 * [GuideLevel.Port]; [marks] are the technician's own marks, drawn only while they mark or check.
 */
data class RegistrationView(
    val guidance: PortGuidance,
    val markerAt: ViewPoint? = null,
    val markerCue: EdgeCue? = null,
    val portAt: ViewPoint? = null,
    val portCue: EdgeCue? = null,
    val marks: List<Pair<String, ViewPoint>> = emptyList(),
    val layout: AssetLayout? = null,
    val note: String? = null,
    /** False when the marker image couldn't be loaded into ARCore, so registration can't run on this phone. */
    val available: Boolean = true,
)

/** Projects the marker, the step's port (only when port-level guidance is earned) and the marks through [view]. */
fun registrationView(view: ArCameraView?, reg: Registration, subject: RegistrationSubject, layout: AssetLayout?, note: String?): RegistrationView {
    val guidance = portGuidance(subject, reg, layout)
    val pose = reg.pose?.takeIf { reg.tracking != MarkerTracking.None }
    if (view == null || pose == null) return RegistrationView(guidance, layout = layout, note = note)
    fun onScreen(w: DoubleArray) = view.project(w)?.takeIf { it.x in 0.0..1.0 && it.y in 0.0..1.0 }?.let { ViewPoint(it.x, it.y) }
    val marker = pose.origin
    val port = if (guidance.level == GuideLevel.Port) subject.port?.let { layout?.point(it) }?.let { pose.apply(it.position) } else null
    val marks = layout?.takeIf { it.fits(subject) }?.points.orEmpty().mapNotNull { p -> onScreen(pose.apply(p.position))?.let { p.label to it } }
    return RegistrationView(
        guidance,
        markerAt = onScreen(marker), markerCue = view.edgeCue(marker),
        portAt = port?.let(::onScreen), portCue = port?.let(view::edgeCue),
        marks = marks, layout = layout, note = note,
    )
}
