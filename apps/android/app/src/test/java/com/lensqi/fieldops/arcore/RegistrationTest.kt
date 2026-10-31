package com.lensqi.fieldops.arcore

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.inspection.registrationSubject
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class RegistrationTest {
    /**
     * A marker upright on a panel [z] metres in front of a camera at the origin, facing it: local x is world x, local y
     * (out of the face) is world +z, local z (down the marker) is world −y. [yawDeg] turns the panel about world y.
     */
    private fun marker(x: Double = 0.0, z: Double = -1.0, yawDeg: Double = 0.0): RigidPose {
        val a = Math.toRadians(yawDeg); val c = cos(a); val s = sin(a)
        return RigidPose(doubleArrayOf(c, 0.0, -s, 0.0, s, 0.0, c, 0.0, 0.0, -1.0, 0.0, 0.0, x, 0.0, z, 1.0))
    }

    private fun camera(): ArCameraView {
        val f = (1 / tan(Math.toRadians(30.0))).toFloat(); val n = 0.05f; val far = 20f
        val p = FloatArray(16).apply { this[0] = f / 0.5625f; this[5] = f; this[10] = (far + n) / (n - far); this[11] = -1f; this[14] = 2 * far * n / (n - far) }
        val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return ArCameraView(m, p, ViewMap.Identity, doubleArrayOf(0.0, 0.0, 0.0))
    }

    private val switch = RegistrationSubject("telecom-lab", 3, "LAB-SW-01", "Lab Switch 01", "ethernet_switch", port = "P2")

    /** Ports 14 mm apart, 10 cm right of the marker and 2 cm below its centre. */
    private fun layout(vararg labels: String, model: String = "ethernet_switch") = labels.fold(AssetLayout("telecom-lab", 3, "LAB-SW-01", model)) { l, label ->
        val n = label.drop(1).toInt()
        l.marked(LayoutPoint(label, 0.10 + (n - 2) * 0.014, 0.0, 0.02, "Marked by the technician on this phone", "2026-10-31T10:00:00Z"))
    }

    private fun registered(pose: RigidPose = marker()) = Registration().apply { update(MarkerTracking.Full, pose); confirmAsset("LAB-SW-01") }

    @Test fun `a point goes into the marker's frame and back, and moves and turns are measured`() {
        val pose = marker(x = 0.3, yawDeg = 25.0)
        val world = pose.apply(doubleArrayOf(0.1, 0.0, 0.02))
        val back = pose.local(world)
        assertEquals(0.1, back[0], 1e-9); assertEquals(0.0, back[1], 1e-9); assertEquals(0.02, back[2], 1e-9)
        // Local z runs down the marker: a point below the centre is lower in the world.
        assertTrue(marker().apply(doubleArrayOf(0.0, 0.0, 0.05))[1] < 0)
        assertEquals(0.3, marker().shift(marker(x = 0.3)), 1e-9)
        assertEquals(25.0, marker().turn(marker(yawDeg = 25.0)), 1e-6)
    }

    @Test fun `neighbouring port labels and the spacing between marked ports`() {
        assertEquals("P1" to "P3", neighbourLabels("P2"))
        assertEquals(null to "P1", neighbourLabels("P0"))
        assertEquals("Gi0/0" to "Gi0/2", neighbourLabels("Gi0/1"))
        assertEquals(null to null, neighbourLabels("WAN"))
        assertEquals(listOf("P1", "P2", "P3"), switch.markable)
        assertNull(portPitch(layout("P2"), "P2"))
        assertEquals(0.014, portPitch(layout("P1", "P2"), "P2")!!, 1e-9)
        assertEquals(0.014, portPitch(layout("P1", "P2", "P3"), "P2")!!, 1e-9)
    }

    @Test fun `guidance only points at the port once the marker, the asset, the layout and the checks all hold`() {
        val reg = Registration()
        assertEquals(GuideLevel.FindMarker, portGuidance(switch, reg, layout("P1", "P2")).level)
        reg.update(MarkerTracking.Full, marker())
        assertEquals("Marker found · is it on Lab Switch 01?", portGuidance(switch, reg, null).text)
        reg.confirmAsset("LAB-SW-01")
        assertEquals("P2 is on Lab Switch 01 · not marked yet. Tap Mark ports, then tap P2", portGuidance(switch, reg, null).text)
        assertTrue(portGuidance(switch, reg, layout("P2")).text.contains("mark a port next to it"))
        reg.check(0.003)
        val one = portGuidance(switch, reg, layout("P1", "P2"))
        assertEquals(GuideLevel.Equipment, one.level); assertTrue(one.text.contains("tap the real P2 (1 more time) before LensQi points at it"))
        reg.check(0.005)
        val port = portGuidance(switch, reg, layout("P1", "P2"))
        assertEquals(GuideLevel.Port, port.level)
        assertEquals("Port P2 · checked to within 5 mm, ports 14 mm apart", port.text)
        // Out of view, the marker's last seen pose still serves, and the screen says so.
        reg.update(MarkerTracking.LastKnown, marker())
        assertTrue(portGuidance(switch, reg, layout("P1", "P2")).text.endsWith("using where it was last seen"))
    }

    @Test fun `an error of half the port spacing or more keeps the guidance at the equipment (adjacent ports)`() {
        val reg = registered(); reg.check(0.004); reg.check(0.007)
        val g = portGuidance(switch, reg, layout("P1", "P2", "P3"))
        assertEquals(GuideLevel.Equipment, g.level)
        assertEquals(7, g.worstErrorMm); assertEquals(14, g.pitchMm)
        assertTrue(g.text.contains("won't point at a port"))
    }

    @Test fun `a refused asset, another model's layout or another asset's confirmation never registers`() {
        val wrong = registered().apply { refuse() }
        assertEquals(GuideLevel.Refused, portGuidance(switch, wrong, layout("P1", "P2")).level)
        wrong.reconsider()
        assertEquals(GuideLevel.ConfirmAsset, portGuidance(switch, wrong, layout("P1", "P2")).level)
        // A similar switch of another model: its layout doesn't fit, so the port counts as unmarked.
        val reg = registered(); reg.check(0.002); reg.check(0.002)
        assertTrue(portGuidance(switch, reg, layout("P1", "P2", model = "ethernet_switch_48")).text.contains("not marked yet"))
        assertEquals(GuideLevel.ConfirmAsset, portGuidance(switch.copy(assetId = "LAB-SW-02"), reg, layout("P1", "P2")).level)
    }

    @Test fun `checks are dropped when the marker moves, turns, is lost, or the layout changes`() {
        fun checked() = registered().apply { check(0.002); check(0.003) }
        val l = layout("P1", "P2")
        val moved = checked().apply { update(MarkerTracking.Full, marker(x = 0.03)) }
        assertEquals("P2 is on Lab Switch 01 · The marker moved · check the port position again", portGuidance(switch, moved, l).text)
        assertTrue(moved.checks.isEmpty())
        assertTrue(checked().apply { update(MarkerTracking.Full, marker(yawDeg = 8.0)) }.checks.isEmpty())
        // Drift within 2 cm and 5° is tracking noise, not a move.
        assertEquals(2, checked().apply { update(MarkerTracking.Full, marker(x = 0.01, yawDeg = 2.0)) }.checks.size)
        val lost = checked().apply { update(MarkerTracking.None, null) }
        assertTrue(lost.checks.isEmpty()); assertEquals("Lost the marker · check the port position again", lost.revalidate)
        assertTrue(checked().apply { layoutChanged() }.checks.isEmpty())
    }

    @Test fun `the port is projected only when port-level guidance is earned`() {
        val l = layout("P1", "P2")
        val early = registrationView(camera(), registered(), switch, l, null)
        assertNull(early.portAt); assertNull(early.portCue)
        assertNotNull(early.markerAt)
        assertEquals(2, early.marks.size)
        val reg = registered().apply { check(0.002); check(0.002) }
        val v = registrationView(camera(), reg, switch, l, "Check 2: 2 mm from the mark")
        val p = v.portAt!!
        // 10 cm right of a marker 1 m ahead: right of centre, a little below it.
        assertTrue(p.x > 0.5 && p.y > 0.5)
        assertNull(v.portCue)
        // With the panel behind the camera, the port is off screen and the cue says so.
        val behind = registrationView(camera(), registered(marker(z = 1.0)).apply { check(0.002); check(0.002) }, switch, l, null)
        assertNull(behind.portAt); assertTrue(behind.portCue!!.behind)
    }

    @Test fun `layouts are stored per fixture and asset and a damaged file reads as none`(@TempDir dir: File) {
        val store = AssetLayoutStore(dir)
        assertNull(store.load("telecom-lab", "LAB-SW-01"))
        store.save(layout("P1", "P2"))
        assertEquals(layout("P1", "P2"), store.load("telecom-lab", "LAB-SW-01"))
        assertNull(store.load("telecom-lab", "LAB-NODE-A"))
        dir.listFiles()!!.single().writeText("{not json")
        assertNull(store.load("telecom-lab", "LAB-SW-01"))
    }

    @Test fun `the subject is the inspection's asset and the port its documented connection uses`() {
        val fixture = NetworkModule.json().decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures")).single()
        val s = registrationSubject(fixture, "LAB-SW-01")!!
        assertEquals("Lab Switch 01", s.assetName); assertEquals("ethernet_switch", s.model); assertEquals("P2", s.port)
        assertNull(registrationSubject(fixture, "LAB-NODE-A")!!.port)
        assertNull(registrationSubject(fixture, "NOT-AN-ASSET"))
        assertFalse(layout("P2").fits(s.copy(model = "router")))
    }
}
