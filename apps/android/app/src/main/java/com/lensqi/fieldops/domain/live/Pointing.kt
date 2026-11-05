package com.lensqi.fieldops.domain.live

import kotlin.math.abs
import kotlin.math.hypot

/*
 * Which part a finger is pointing at, worked out on the phone from hand landmarks and the boxes the camera already has.
 * It only ever says where a finger is in the picture. It cannot tell whether the finger touches anything, and nothing here
 * reaches a step check, a verdict or the verification gate: it adds a note to a question the person chose to ask.
 */

/** A hand landmark. Image landmarks are fractions of the upright frame; world landmarks are metres around the hand. */
data class HandPoint(val x: Double, val y: Double, val z: Double = 0.0)

/**
 * One hand from the detector: 21 landmarks in its order (wrist, then four joints per finger from the thumb out). [score] is
 * the only per-hand confidence the detector gives. [world] stays reliable when a finger points away from the camera and looks
 * short in the picture, so finger shape is read from it when present.
 */
class HandReading(val image: List<HandPoint>, val world: List<HandPoint>, val score: Double)

/** An extended index finger: its tip in the frame, the way it points in the picture, and how far along that way to look. */
data class Pointer(val x: Double, val y: Double, val dx: Double, val dy: Double, val reach: Double)

private const val LANDMARKS = 21
private const val WRIST = 0
private const val INDEX_MCP = 5
private const val INDEX_PIP = 6
private const val INDEX_TIP = 8
private const val MIDDLE_MCP = 9
private const val RING_MCP = 13
private const val PINKY_MCP = 17

/** Below this a hand is not trusted enough to name a part. */
const val MIN_HAND_SCORE = 0.6
private const val MIN_PALM = 0.03

private fun dist3(a: HandPoint, b: HandPoint) = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

/** Reaches away from the wrist and is nearly straight at the middle joint. [mcp] is the finger's first landmark. */
private fun extended(hand: List<HandPoint>, mcp: Int): Boolean {
    val wrist = hand[WRIST]; val base = hand[mcp]; val pip = hand[mcp + 1]; val tip = hand[mcp + 3]
    if (dist3(wrist, tip) <= dist3(wrist, pip) * 1.1) return false
    val ax = pip.x - base.x; val ay = pip.y - base.y; val az = pip.z - base.z
    val bx = tip.x - pip.x; val by = tip.y - pip.y; val bz = tip.z - pip.z
    val lengths = kotlin.math.sqrt(ax * ax + ay * ay + az * az) * kotlin.math.sqrt(bx * bx + by * by + bz * bz)
    return lengths > 0 && (ax * bx + ay * by + az * bz) / lengths > 0.6
}

/**
 * The pointing finger of [hand], or null when it isn't pointing: too uncertain, a malformed reading, the tip out of frame, a
 * hand too small to read, or another shape. Index out, middle in and at least one of ring or little in: a fist, an open hand,
 * a thumbs-up and two fingers up are not pointing, so working hands and tools don't name parts by accident.
 * [aspect] is frame width over height, so directions are measured in real proportions.
 */
fun readPointer(hand: HandReading, aspect: Double): Pointer? {
    if (hand.score < MIN_HAND_SCORE || !(aspect > 0.0) || hand.image.size != LANDMARKS) return null
    if (hand.image.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val tip = hand.image[INDEX_TIP]; val pip = hand.image[INDEX_PIP]; val mcp = hand.image[INDEX_MCP]
    if (tip.x !in 0.0..1.0 || tip.y !in 0.0..1.0) return null
    val shape = hand.world.takeIf { w -> w.size == LANDMARKS && w.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() } }
        ?: hand.image.map { HandPoint(it.x * aspect, it.y, it.z) }
    if (!extended(shape, INDEX_MCP) || extended(shape, MIDDLE_MCP)) return null
    if (extended(shape, RING_MCP) && extended(shape, PINKY_MCP)) return null
    // The palm says how big the hand is: a finger aimed away from the camera looks short and is still a good reading.
    val wrist = hand.image[WRIST]; val palmEnd = hand.image[MIDDLE_MCP]
    if (hypot((palmEnd.x - wrist.x) * aspect, palmEnd.y - wrist.y) < MIN_PALM) return null
    val length = hypot((tip.x - mcp.x) * aspect, tip.y - mcp.y)
    val dx = (tip.x - pip.x) * aspect; val dy = tip.y - pip.y
    val norm = hypot(dx, dy)
    // Foreshortened until the joints meet: the tip itself still counts, but it says nothing about a direction.
    return if (norm < 1e-3) Pointer(tip.x, tip.y, 0.0, 0.0, 0.0) else Pointer(tip.x, tip.y, dx / norm, dy / norm, length * 1.5)
}

/** What a fingertip resolved to: nothing, one part, or two it can't choose between. */
sealed interface PointedPart {
    data object None : PointedPart
    data class One(val part: LiveObject) : PointedPart
    data class Between(val first: LiveObject, val second: LiveObject) : PointedPart
}

private const val MAX_SEEN_SECONDS = 10
private const val BOX_MARGIN = 0.01
private const val RAY_STEPS = 6
private val HAND_WORDS = setOf("hand", "hands", "finger", "fingers", "thumb", "fingertip")

/**
 * The part under the tip, or else the first one along the way the finger points, at most [Pointer.reach] past the tip.
 * Boxes are in the parts' own coordinates, so [toParts] carries a frame point there (the view, in AR). A part the camera has
 * lost track of doesn't count: an uncertain one, one only remembered for more than ten seconds, or a detection of the hand.
 */
fun pointedPart(pointer: Pointer, parts: List<LiveObject>, aspect: Double, toParts: (Double, Double) -> Pair<Double, Double>): PointedPart {
    val usable = parts.filter { !it.uncertain && (it.seenSecondsAgo ?: 0) <= MAX_SEEN_SECONDS && it.concept.trim().lowercase() !in HAND_WORDS }
    if (usable.isEmpty()) return PointedPart.None
    for (step in 0..RAY_STEPS) {
        val along = pointer.reach * step / RAY_STEPS
        val (x, y) = toParts(pointer.x + pointer.dx * along / aspect, pointer.y + pointer.dy * along)
        val hits = usable.filter { x in it.box.left - BOX_MARGIN..it.box.right + BOX_MARGIN && y in it.box.top - BOX_MARGIN..it.box.bottom + BOX_MARGIN }
        if (hits.isNotEmpty()) return choose(hits)
    }
    return PointedPart.None
}

private fun area(b: LiveBox) = (b.right - b.left) * (b.bottom - b.top)

private fun overlap(a: LiveBox, b: LiveBox): Double {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left); val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w <= 0 || h <= 0) 0.0 else w * h
}

/** The smaller box is the more specific part, as a button in a panel. Two of about the same size, side by side, stay undecided. */
private fun choose(hits: List<LiveObject>): PointedPart {
    val sorted = hits.sortedBy { area(it.box) }
    val first = sorted[0]
    val second = sorted.getOrNull(1) ?: return PointedPart.One(first)
    val nested = overlap(first.box, second.box) >= area(first.box) * 0.8
    val sameKind = first.concept.equals(second.concept, ignoreCase = true)
    return if (nested || sameKind || area(second.box) >= area(first.box) * 2) PointedPart.One(first) else PointedPart.Between(first, second)
}

/** Where a person is pointing, once it has held long enough to mean it. */
data class PointedAt(
    /** The tip as a fraction of the upright camera frame, which is what a question's photo shows. */
    val x: Double, val y: Double,
    /** The same point in the parts' coordinates, for drawing. */
    val viewX: Double, val viewY: Double,
    val pointed: PointedPart,
    val time: Long,
)

/** The pointing that goes with a question, and the frame taken at that moment. */
class PointingSnapshot(val at: PointedAt, val jpeg: ByteArray?)

/** What the screen shows: the finger now, or where it last pointed while that is still kept for a question. */
data class PointingView(val now: PointedAt? = null, val kept: PointedAt? = null)

/**
 * Steadies raw hand readings into something a person can read and a question can use. A finger must hold for a moment before
 * it counts, a brief dropout doesn't end it, and moving to another part must hold too, so the state doesn't flicker between
 * neighbours. Used from one thread at a time, except [take] and [reset], so all of it is guarded.
 */
class PointingTracker(
    private val settleMs: Long = 250,
    private val switchMs: Long = 300,
    private val lostAfterMs: Long = 400,
    /** How long the last pointing stays available: a spoken question often finishes after the hand has come down. */
    private val keepMs: Long = 8_000,
    private val deadZone: Double = 0.004,
    private val follow: Double = 0.5,
    private val snap: Double = 0.15,
) {
    private var sx = 0.0; private var sy = 0.0; private var sdx = 0.0; private var sdy = 0.0
    private var since = -1L; private var lastSeen = -1L
    private var shown: PointedAt? = null
    private var shownKey: String? = null
    private var pendingKey: String? = null; private var pendingSince = 0L
    private var kept: PointedAt? = null
    private var snapshot: PointingSnapshot? = null
    private var snapshotAt = -1L
    private var snapshotKey: String? = null

    /** One analysed frame: every hand found (none is the usual case), and the parts the camera knows now. */
    @Synchronized
    fun update(now: Long, hands: List<HandReading>, aspect: Double, parts: List<LiveObject>, toParts: (Double, Double) -> Pair<Double, Double>): PointingView {
        val best = hands.mapNotNull { hand -> readPointer(hand, aspect)?.let { it to hand.score } }.maxByOrNull { it.second }?.first
        val visible = best?.let { toParts(it.x, it.y) }?.takeIf { (x, y) -> x in 0.0..1.0 && y in 0.0..1.0 }
        if (best == null || visible == null) {
            if (shown != null && now - lastSeen > lostAfterMs) clearShown()
            else if (shown == null) { since = -1; pendingKey = null }
            shown?.let { keep(it.copy(time = now)) }
            return view(now)
        }
        val moved = since >= 0 && now - lastSeen <= lostAfterMs && maxOf(abs(best.x - sx), abs(best.y - sy)) <= snap
        if (moved) {
            if (maxOf(abs(best.x - sx), abs(best.y - sy)) > deadZone) { sx += (best.x - sx) * follow; sy += (best.y - sy) * follow }
            sdx += (best.dx - sdx) * follow; sdy += (best.dy - sdy) * follow
        } else {
            // A first sighting, or a jump too big to be one finger moving: a new pointing, which has to settle again.
            if (shown != null) clearShown()
            sx = best.x; sy = best.y; sdx = best.dx; sdy = best.dy; since = now; pendingKey = null
        }
        lastSeen = now
        if (now - since < settleMs) return view(now)
        val norm = hypot(sdx, sdy)
        val pointer = Pointer(sx, sy, if (norm > 0) sdx / norm else 0.0, if (norm > 0) sdy / norm else 0.0, if (norm > 0) best.reach else 0.0)
        val found = pointedPart(pointer, parts, aspect, toParts)
        val pointed = settled(now, found, parts)
        val (vx, vy) = toParts(sx, sy)
        val at = PointedAt(sx, sy, vx, vy, pointed, now)
        shown = at
        keep(at)
        return view(now)
    }

    /** A different answer replaces the one on screen only after holding; a part that has gone from the camera goes at once. */
    private fun settled(now: Long, found: PointedPart, parts: List<LiveObject>): PointedPart {
        val key = keyOf(found)
        val current = shown
        if (current == null || key == shownKey) { shownKey = key; pendingKey = null; return found }
        if (key != pendingKey) { pendingKey = key; pendingSince = now }
        if (now - pendingSince >= switchMs) { shownKey = key; pendingKey = null; return found }
        return current.pointed.takeIf { stillThere(it, parts) } ?: run { shownKey = key; pendingKey = null; found }
    }

    private fun stillThere(pointed: PointedPart, parts: List<LiveObject>): Boolean {
        val keys = parts.map(::partKey).toSet()
        return when (pointed) {
            PointedPart.None -> true
            is PointedPart.One -> partKey(pointed.part) in keys
            is PointedPart.Between -> partKey(pointed.first) in keys && partKey(pointed.second) in keys
        }
    }

    private fun partKey(part: LiveObject) = part.anchorId ?: part.concept.lowercase()

    private fun keyOf(pointed: PointedPart) = when (pointed) {
        PointedPart.None -> "none"
        is PointedPart.One -> partKey(pointed.part)
        is PointedPart.Between -> listOf(partKey(pointed.first), partKey(pointed.second)).sorted().joinToString("|")
    }

    private fun keep(at: PointedAt) { kept = at }

    private fun clearShown(keepLast: Boolean = true) {
        shown = null; shownKey = null; pendingKey = null; since = -1
        if (!keepLast) kept = null
    }

    private fun view(now: Long): PointingView {
        if (kept?.let { now - it.time > keepMs } == true) { kept = null; snapshot = null; snapshotAt = -1; snapshotKey = null }
        return PointingView(shown, kept.takeIf { shown == null })
    }

    /** True when the frame just analysed should be kept with the pointing: when it changes, and about once a second while it holds. */
    @Synchronized
    fun wantsSnapshot(now: Long): Boolean {
        val at = shown ?: return false
        return snapshot == null || keyOf(at.pointed) != snapshotKey || now - snapshotAt >= 1_000
    }

    /** Keeps [jpeg], the frame [wantsSnapshot] asked for. It stays in memory on the phone unless a question is asked. */
    @Synchronized
    fun storeSnapshot(jpeg: ByteArray?, now: Long) {
        val at = shown ?: return
        snapshot = PointingSnapshot(at, jpeg); snapshotAt = now; snapshotKey = keyOf(at.pointed)
    }

    /** The pointing for a question being asked now, with its frame. It is used once: the next question starts clean. */
    @Synchronized
    fun take(now: Long): PointingSnapshot? {
        val at = shown ?: kept?.takeIf { now - it.time <= keepMs } ?: return null
        val result = snapshot?.takeIf { keyOf(it.at.pointed) == keyOf(at.pointed) } ?: PointingSnapshot(at, null)
        kept = null; snapshot = null; snapshotAt = -1; snapshotKey = null
        if (shown != null) clearShown()
        return result
    }

    /** A new step, task or inspection: nothing pointed at before it carries over. */
    @Synchronized
    fun reset() {
        clearShown(keepLast = false)
        lastSeen = -1; snapshot = null; snapshotAt = -1; snapshotKey = null
    }
}

/** How often to look for a hand: at the camera's analysis rate while one is around, and slower when none is, to save battery. */
fun pointingIntervalMs(lastHandAt: Long, now: Long): Long = if (lastHandAt >= 0 && now - lastHandAt <= 2_000) 100 else 250

private fun cleanLabel(label: String) = label.filter { !it.isISOControl() && it != '"' && it != '[' && it != ']' }.trim().take(60)

private const val NOTE_START = " [Fingertip in the photo: "
private const val MAX_QUESTION = 500

/**
 * The question as sent, with where the person was pointing. It describes the picture and nothing else: no instruction, no
 * claim of contact, and the label marked as the AI's. When the two don't fit the server's limit, the person's words win.
 */
fun pointedQuestion(question: String, at: PointedAt?): String {
    val q = question.trim()
    if (at == null) return q
    val where = "${(at.x * 100).toInt()}% across, ${(at.y * 100).toInt()}% down"
    val what = when (val p = at.pointed) {
        PointedPart.None -> ""
        is PointedPart.One -> ", on what the camera labelled \"${cleanLabel(p.part.label)}\" (AI suggestion)"
        is PointedPart.Between -> ", between what the camera labelled \"${cleanLabel(p.first.label)}\" and \"${cleanLabel(p.second.label)}\" (AI suggestions)"
    }
    return "$q$NOTE_START$where$what.]".takeIf { it.length <= MAX_QUESTION } ?: q
}

/** A recorded question in the person's own words, without the note [pointedQuestion] added. */
fun withoutPointingNote(question: String): String = question.substringBefore(NOTE_START).trim()

/** What the screen says about the pointing, marked as the AI's where a label is involved. */
fun pointingText(at: PointedAt, kept: Boolean = false): String {
    val verb = if (kept) "Pointed" else "Pointing"
    return when (val p = at.pointed) {
        PointedPart.None -> if (kept) "Pointed at a spot in the picture" else "Pointing at this spot"
        is PointedPart.One -> "$verb at the ${p.part.label.lowercase()} · " +
            (if (p.part.target) "your target" else "AI suggestion") + (p.part.seenSecondsAgo?.let { " · last seen $it s ago" } ?: "")
        is PointedPart.Between -> "$verb between the ${p.first.label.lowercase()} and the ${p.second.label.lowercase()} · AI suggestions"
    }
}

/** The short form beside a question already asked. */
fun pointingSuffix(at: PointedAt?): String = when (val p = at?.pointed) {
    null -> ""
    PointedPart.None -> " · while pointing"
    is PointedPart.One -> " · while pointing at the ${p.part.label.lowercase()} (AI suggestion)"
    is PointedPart.Between -> " · while pointing between the ${p.first.label.lowercase()} and the ${p.second.label.lowercase()} (AI suggestions)"
}

/**
 * Whether a camera screen looks for a pointing finger. Screens that take no questions never offer it, so evidence capture
 * can't pick it up. Professionals-only work turns it off: a finger reaching toward a panel or a gas line is what those plans
 * are written to avoid, so the app shouldn't invite the gesture, and tapping an outline still works from a safe distance.
 */
enum class PointingUse { NotOffered, OffForThisWork, Allowed }

fun pointingUse(restricted: String?, professionalRequired: Boolean): PointingUse =
    if (professionalRequired || !(restricted.isNullOrBlank() || restricted.equals("None", ignoreCase = true))) PointingUse.OffForThisWork else PointingUse.Allowed

const val POINTING_OFF_NOTE = "Finger pointing is off for this job. Keep your hands back; tap an outline to ask about a part."
const val POINTING_LIMITS_NOTE = "Experimental. Turn it on, point at a part, then ask about it. It runs on this phone and works from the picture only: it can't tell whether you are touching anything, and it may miss gloved hands."
