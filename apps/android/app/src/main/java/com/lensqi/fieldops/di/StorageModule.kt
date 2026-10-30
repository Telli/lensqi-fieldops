package com.lensqi.fieldops.di

import android.content.Context
import androidx.room.Room
import com.lensqi.fieldops.data.SnapshotStore
import com.lensqi.fieldops.data.queue.CommandQueue
import com.lensqi.fieldops.data.queue.PendingCommandDao
import com.lensqi.fieldops.data.queue.StepCheckQueue
import com.lensqi.fieldops.voice.Speaker
import com.lensqi.fieldops.voice.SpeechOutput
import com.lensqi.fieldops.data.queue.QueueDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): QueueDatabase =
        Room.databaseBuilder(context, QueueDatabase::class.java, "fieldops-queue.db").addMigrations(QueueDatabase.MIGRATION_1_2).build()

    @Provides @Singleton
    fun snapshots(@ApplicationContext context: Context, json: Json): SnapshotStore = SnapshotStore(File(context.filesDir, "snapshots"), json)

    @Provides
    fun commands(db: QueueDatabase): PendingCommandDao = db.commands()

    @Provides
    fun stepChecks(queue: CommandQueue): StepCheckQueue = queue

    @Provides
    fun speech(speaker: Speaker): SpeechOutput = speaker
}
