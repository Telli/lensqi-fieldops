package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.*
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.receipt.sameWebOrigin
import com.lensqi.fieldops.ui.task.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MobileIsolationTest {
    @Test fun `snapshots and memory views never cross servers or inspections`(@TempDir dir: File) = runBlocking {
        val json = NetworkModule.json()
        val knowledge = json.decodeFromString(KnowledgeResponse.serializer(), Recordings.text("knowledge"))
        val a = SnapshotStore.key("https://a.test/", "knowledge", "A")
        val b = SnapshotStore.key("https://a.test/", "knowledge", "B")
        val c = SnapshotStore.key("https://b.test/", "knowledge", "A")
        val store = SnapshotStore(dir, json)
        store.save(a, KnowledgeResponse.serializer(), knowledge.copy(packId = "pack-A"))
        store.save(b, KnowledgeResponse.serializer(), knowledge.copy(packId = "pack-B"))
        assertEquals("pack-A", SnapshotStore(dir, json).load(a, KnowledgeResponse.serializer())!!.value.packId)
        assertNull(store.load(c, KnowledgeResponse.serializer()))
        val view = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection-created"))
        val cache = InspectionCache()
        cache.put(view, "https://a.test/")
        assertEquals(view, cache.observe(view.inspectionId, "https://a.test/").first())
        assertNull(cache.observe(view.inspectionId, "https://b.test/").first())
    }
    @Test fun `receipt links require an exact scheme host and port`() {
        val origin = "https://records.example.test"
        assertTrue(sameWebOrigin(origin, "$origin:443/a"))
        for (url in listOf("${origin}.evil.test", "$origin@evil.test/a", "$origin:444/a", "http://records.example.test/a", "javascript:alert(1)", "file:///tmp/a"))
            assertFalse(sameWebOrigin(origin, url), url)
    }
    @Test fun `history retains older and stopped tasks and filters without hiding active work`() {
        val rows = (1..12).map { TaskSummary("t$it", TaskMode.Diy, "job $it", phase = if (it == 12) TaskPhase.Abandoned else TaskPhase.InProgress, steps = 2, finished = 0, updatedAt = "now") }
        assertEquals(12, taskHistory(rows, TaskFilter.All, "").size)
        assertEquals(listOf("t12"), taskHistory(rows, TaskFilter.Stopped, "JOB 12").map { it.taskId })
        assertEquals(11, taskHistory(rows, TaskFilter.Active, "").size)
        assertTrue(taskHistory(rows, TaskFilter.Completed, "").isEmpty())
        val stopped = rows.last().copy(mode = TaskMode.Freeform)
        assertEquals("Stopped", taskStatus(stopped))
        assertEquals("AI-named · Stopped", taskStatus(stopped.copy(title = "Loose patch cable", titleByAi = true)))
        assertEquals("task/t12/done", taskRoute(stopped))
        assertEquals("task/t12/done", taskRoute(stopped.copy(phase = TaskPhase.Completed)))
        assertEquals("task/t12/live", taskRoute(stopped.copy(phase = TaskPhase.InProgress)))
    }
    @Test fun `switching from technician to DIY does not submit a hidden site`() {
        val site = "fixture" to "asset"
        assertEquals(site, siteForMode(TaskMode.Technician, site))
        assertNull(siteForMode(TaskMode.Diy, site))
        assertNull(siteForMode(TaskMode.Freeform, site))
    }
}
