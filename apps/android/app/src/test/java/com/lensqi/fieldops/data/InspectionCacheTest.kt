package com.lensqi.fieldops.data

import app.cash.turbine.test
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InspectionCacheTest {
    private val json = NetworkModule.json()
    private val newer = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection"))
    private val older = newer.copy(lastSequence = 3)

    @Test
    fun a_late_older_response_never_replaces_a_newer_server_view() = runTest {
        val cache = InspectionCache()
        cache.observe(newer.inspectionId).test {
            assertNull(awaitItem())
            cache.put(newer)
            assertEquals(newer.lastSequence, awaitItem()!!.lastSequence)
            cache.put(older)
            expectNoEvents()
            cache.put(newer.copy(lastSequence = newer.lastSequence + 1))
            assertEquals(newer.lastSequence + 1, awaitItem()!!.lastSequence)
        }
    }
}
