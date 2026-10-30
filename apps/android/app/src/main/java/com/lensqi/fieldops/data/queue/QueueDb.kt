package com.lensqi.fieldops.data.queue

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** UploadPhoto is inspection evidence; StepCheckPhoto is a guided-task step check (GT-R09.5). */
enum class CommandKind { UploadPhoto, StepCheckPhoto }

enum class CommandState { Pending, Rejected }

/**
 * One command waiting for the server, in capture order. The id is the Idempotency-Key, so a command replayed
 * after a crash or a lost response is applied once. Nothing here is evidence until the server accepts it.
 * [sessionId] is the session the command belongs to (GT-R12.1): an inspection for a photo, or a task for a step check
 * (with [stepId]). The column keeps its version-1 name so queued commands survive upgrades without a table rebuild.
 */
@Entity(tableName = "pending_command")
data class PendingCommand(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspectionId") val sessionId: String,
    val kind: CommandKind,
    val createdAtMillis: Long,
    val filePath: String? = null,
    val mediaType: String? = null,
    val sha256: String? = null,
    val source: String? = null,
    val observedAt: String? = null,
    val state: CommandState = CommandState.Pending,
    val attempts: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val stepId: String? = null,
)

@Dao
interface PendingCommandDao {
    @Insert
    suspend fun insert(command: PendingCommand)

    @Query("SELECT * FROM pending_command WHERE inspectionId = :sessionId ORDER BY createdAtMillis")
    fun observe(sessionId: String): Flow<List<PendingCommand>>

    @Query("SELECT * FROM pending_command WHERE state = 'Pending' ORDER BY createdAtMillis LIMIT 1")
    suspend fun nextPending(): PendingCommand?

    @Query("UPDATE pending_command SET attempts = attempts + 1 WHERE id = :id")
    suspend fun countAttempt(id: String)

    @Query("UPDATE pending_command SET state = 'Rejected', errorCode = :code, errorMessage = :message WHERE id = :id")
    suspend fun reject(id: String, code: String, message: String)

    @Query("DELETE FROM pending_command WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [PendingCommand::class], version = 2, exportSchema = false)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun commands(): PendingCommandDao

    companion object {
        /** Version 2 adds the step of a queued step check; queued inspection photos are kept. */
        const val MIGRATION_1_2_SQL = "ALTER TABLE pending_command ADD COLUMN stepId TEXT"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(MIGRATION_1_2_SQL)
        }
    }
}
