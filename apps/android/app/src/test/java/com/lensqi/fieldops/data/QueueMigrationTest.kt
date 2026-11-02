package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.queue.QueueDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 1→2 upgrade of the phone's queue on real SQLite: a photo queued by version 1 survives, and the migrated table has
 * exactly the columns Room's generated code for the current version expects, so Room opens it without a reset.
 */
class QueueMigrationTest {
    /** Room's table for version 1 (a51512b^), before step checks were queued. */
    private val version1 = "CREATE TABLE IF NOT EXISTS `pending_command` (`id` TEXT NOT NULL, `inspectionId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, `filePath` TEXT, `mediaType` TEXT, `sha256` TEXT, `source` TEXT, `observedAt` TEXT, `state` TEXT NOT NULL, " +
        "`attempts` INTEGER NOT NULL, `errorCode` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))"

    /** The table Room creates for the current version, read from its generated code so this test follows the entity. */
    private val current: String = File("build/generated/ksp/debug/kotlin/com/lensqi/fieldops/data/queue/QueueDatabase_Impl.kt").readText()
        .let { Regex("""execSQL\("(CREATE TABLE IF NOT EXISTS `pending_command`[^"]+)"\)""").find(it)!!.groupValues[1] }

    private fun memory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun columns(c: Connection): List<String> = c.createStatement().executeQuery("PRAGMA table_info(pending_command)").use { r ->
        buildList { while (r.next()) add("${r.getString("name")} ${r.getString("type")} notnull=${r.getInt("notnull")} pk=${r.getInt("pk")}") }
    }.sorted()

    @Test
    fun `a photo queued by version 1 survives the upgrade and the table matches what Room expects now`() {
        memory().use { old ->
            old.createStatement().execute(version1)
            old.createStatement().execute("INSERT INTO pending_command (id, inspectionId, kind, createdAtMillis, filePath, mediaType, sha256, source, observedAt, state, attempts) " +
                "VALUES ('k-1', 'I-1', 'UploadPhoto', 1, '/captures/k-1.jpg', 'image/jpeg', 'abc', 'Phone', '2026-10-30T00:00:00Z', 'Pending', 0)")
            old.createStatement().execute(QueueDatabase.MIGRATION_1_2_SQL)
            QueueDatabase.MIGRATION_2_3_SQL.forEach { old.createStatement().execute(it) }

            memory().use { fresh ->
                fresh.createStatement().execute(current)
                assertEquals(columns(fresh), columns(old))
            }
            old.createStatement().executeQuery("SELECT id, inspectionId, kind, filePath, stepId, state, serverBase, errorCode, afterRepair FROM pending_command").use { r ->
                r.next()
                assertEquals(listOf("k-1", "I-1", "UploadPhoto", "/captures/k-1.jpg"), (1..4).map(r::getString))
                assertNull(r.getString("stepId"))
                assertEquals("Rejected", r.getString("state"))
                assertNull(r.getString("serverBase"))
                assertEquals("server_unbound", r.getString("errorCode"))
                assertEquals(0, r.getInt("afterRepair"))
            }
        }
    }
}
