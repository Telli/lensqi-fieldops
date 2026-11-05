package com.lensqi.fieldops.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lensqi.fieldops.domain.live.HandReading
import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.domain.live.PointedPart
import com.lensqi.fieldops.domain.live.PointingTracker
import com.lensqi.fieldops.domain.live.pointedPart
import com.lensqi.fieldops.domain.live.readPointer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real detector on real photos of hands (MediaPipe's own test images, fetched at build time), through the same code that
 * reads the camera. A virtual scene has no hand in it; this is how far a machine without a person can check the model.
 */
@RunWith(AndroidJUnit4::class)
class HandDetectorPhotoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val detector = HandDetector(instrumentation.targetContext.also { it.getSharedPreferences("hand_detector", 0).edit().clear().commit() })
    private var clock = 0L

    @After fun close() = detector.close()

    private fun photo(name: String): Bitmap = instrumentation.context.assets.open("hands/$name.jpg").use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
    }

    /** A photo as the camera's analyzer hands it over: longest side 640. */
    private fun asFrame(source: Bitmap): Bitmap {
        val scale = minOf(1f, 640f / maxOf(source.width, source.height))
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
    }

    /** A phone must run the model. An emulator on an Apple M4 can't, on its CPU or its GPU; there these tests are skipped, not passed. */
    private fun hands(bitmap: Bitmap): List<HandReading> = detector.detect(bitmap, ++clock).also {
        assumeFalse("this emulator can't run the model", detector.failed && Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse("detector failed to run", detector.failed)
    }

    private fun pointers(name: String): List<com.lensqi.fieldops.domain.live.Pointer> {
        val frame = asFrame(photo(name))
        return hands(frame).mapNotNull { readPointer(it, frame.width.toDouble() / frame.height) }
    }

    @Test fun aPhotoOfAPointingHandIsPointingWithTheTipInTheFrame() {
        val frame = asFrame(photo("pointing_up"))
        val found = hands(frame)
        assertTrue("a hand should be found", found.isNotEmpty())
        val pointer = readPointer(found.first(), frame.width.toDouble() / frame.height)
        assertNotNull("this hand is pointing", pointer)
        assertTrue(pointer!!.x in 0.0..1.0 && pointer.y in 0.0..1.0)
        assertTrue("the finger points up the picture, got dy=${pointer.dy}", pointer.dy < -0.5)
        Log.i("HandDetectorPhotoTest", "pointing_up tip=(${pointer.x}, ${pointer.y}) dir=(${pointer.dx}, ${pointer.dy}) score=${found.first().score}")
    }

    @Test fun otherHandShapesAreNotPointing() {
        for (name in listOf("thumb_up", "victory", "fist")) {
            val found = pointers(name)
            assertEquals("$name should not read as pointing", 0, found.size)
        }
    }

    @Test fun aPhotoWithNoHandFindsNothing() {
        assertTrue(hands(asFrame(photo("cats_and_dogs"))).isEmpty())
    }

    @Test fun anEmptyFrameFindsNothing() {
        assertTrue(hands(Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)).isEmpty())
    }

    @Test fun thePointingPhotoResolvesToAPartPlacedUnderItsTip() {
        val frame = asFrame(photo("pointing_up"))
        val aspect = frame.width.toDouble() / frame.height
        val pointer = readPointer(hands(frame).first(), aspect)!!
        val under = LiveObject("1", "test button", "Test button", LiveBox((pointer.x - 0.05).coerceAtLeast(0.0), (pointer.y - 0.05).coerceAtLeast(0.0), (pointer.x + 0.05).coerceAtMost(1.0), (pointer.y + 0.05).coerceAtMost(1.0)))
        val elsewhere = LiveObject("2", "vent", "Vent", LiveBox(0.0, 0.9, 0.1, 1.0))
        assertEquals(PointedPart.One(under), pointedPart(pointer, listOf(elsewhere, under), aspect) { x, y -> x to y })
        assertEquals(PointedPart.None, pointedPart(pointer, listOf(elsewhere), aspect) { x, y -> x to y })
    }

    @Test fun repeatedFramesThroughTheTrackerSettleOnThePart() {
        val frame = asFrame(photo("pointing_up"))
        val aspect = frame.width.toDouble() / frame.height
        val tracker = PointingTracker()
        val tip = readPointer(hands(frame).first(), aspect)!!
        val part = LiveObject("1", "test button", "Test button", LiveBox((tip.x - 0.1).coerceAtLeast(0.0), (tip.y - 0.1).coerceAtLeast(0.0), (tip.x + 0.1).coerceAtMost(1.0), (tip.y + 0.1).coerceAtMost(1.0)))
        var now = 0L
        repeat(6) { tracker.update(now, hands(frame), aspect, listOf(part)) { x, y -> x to y }; now += 100 }
        val settled = tracker.take(now)
        assertNotNull("the pointing should have settled", settled)
        assertEquals(PointedPart.One(part), settled!!.at.pointed)
        assertNull("used once", tracker.take(now))
    }

    @Test fun reportsHowLongOneDetectionTakes() {
        val frame = asFrame(photo("pointing_up"))
        repeat(5) { hands(frame) } // warm up
        val started = SystemClock.elapsedRealtime()
        repeat(20) { hands(frame) }
        val each = (SystemClock.elapsedRealtime() - started) / 20
        Log.i("HandDetectorPhotoTest", "one detection with a hand in view: $each ms on this machine")
        assertTrue("got $each ms", each < 2000)
    }

    @Test fun aDetectorThatDiedOnItsLastStartStaysOff() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("hand_detector", 0).edit().putBoolean("starting", true).commit()
        val next = HandDetector(context)
        assertTrue(next.failed)
        assertTrue(next.detect(asFrame(photo("pointing_up")), ++clock).isEmpty())
        next.close()
        context.getSharedPreferences("hand_detector", 0).edit().clear().commit()
    }

    @Test fun askingAgainClearsThatMark() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("hand_detector", 0).edit().putBoolean("starting", true).commit()
        HandDetector.retry(context)
        val next = HandDetector(context)
        assertFalse(next.failed)
        next.close()
    }

    @Test fun aSuccessfulFirstDetectionLeavesNoMark() {
        hands(asFrame(photo("cats_and_dogs")))
        assertFalse(instrumentation.targetContext.getSharedPreferences("hand_detector", 0).contains("starting"))
    }

    @Test fun aClosedDetectorFindsNothingAndDoesNotCrash() {
        detector.close()
        assertTrue(detector.detect(asFrame(photo("pointing_up")), ++clock).isEmpty())
    }
}
