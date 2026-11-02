package com.lensqi.fieldops.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lensqi.fieldops.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsStoreTest {
    @Test fun `a server address gets a trailing slash on its path and a query or fragment is refused`() {
        assertEquals("http://10.0.2.2:5201/", SettingsStore.normalize(" http://10.0.2.2:5201 ").toString())
        assertEquals("https://example.com/api/", SettingsStore.normalize("https://example.com/api").toString())
        assertEquals("https://example.com/api/", SettingsStore.normalize("https://example.com/api/").toString())
        assertNull(SettingsStore.normalize("https://example.com/api?x=1"))
        assertNull(SettingsStore.normalize("https://example.com/api#top"))
        assertNull(SettingsStore.normalize("ftp://example.com/"))
    }

    @Test fun `an unreadable preferences file falls back to the default server`(@TempDir dir: File) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(dir, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37)) }
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(SettingsStore.DEFAULT_BASE, settings.apiBase.first())
            assertEquals(SettingsStore.normalize(SettingsStore.DEFAULT_BASE), settings.currentBase())
        } finally { scope.cancel() }
    }
}
