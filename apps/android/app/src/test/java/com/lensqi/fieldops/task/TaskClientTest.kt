package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.api.CreateTaskResponse
import com.lensqi.fieldops.data.api.RiskLevel
import com.lensqi.fieldops.data.api.TaskPhase
import com.lensqi.fieldops.voice.VoiceCommand
import com.lensqi.fieldops.voice.parseCommand
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TaskClientTest {
    @Test fun `whole-utterance voice commands match the web grammar`() {
        assertEquals(VoiceCommand.Next, parseCommand("Next step"))
        assertEquals(VoiceCommand.Next, parseCommand("I'm done."))
        assertEquals(VoiceCommand.Check, parseCommand("Hey LensQi, check this"))
        assertEquals(VoiceCommand.Check, parseCommand("take a photo please"))
        assertEquals(VoiceCommand.Back, parseCommand("go back"))
        assertEquals(VoiceCommand.Repeat, parseCommand("Say that again"))
        assertEquals(VoiceCommand.Skip, parseCommand("skip this step"))
        assertEquals(VoiceCommand.Mute, parseCommand("stop talking"))
    }

    @Test fun `anything more is a question`() {
        assertNull(parseCommand("is this the drain plug"))
        assertNull(parseCommand("check whether the camera is level"))
        assertNull(parseCommand(""))
    }

    /** The app's JSON settings (NetworkModule) against a response the real API returned for a genuine Muse plan. */
    @Test fun `a recorded server plan decodes with the app's JSON settings`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val r = json.decodeFromString(CreateTaskResponse.serializer(), File("../../../docs/evidence/tasks/plan-oil.json").readText())
        val plan = r.task.plan!!
        assertEquals(TaskPhase.Planned, r.task.phase)
        assertEquals(RiskLevel.Moderate, plan.risk)
        assertEquals("s1", plan.steps.first().id)
        assertTrue(plan.sourceNote.startsWith("AI-written plan"))
        assertTrue(plan.steps.any { it.check?.required == true })
    }
}
