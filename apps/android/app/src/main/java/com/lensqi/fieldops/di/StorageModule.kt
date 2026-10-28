package com.lensqi.fieldops.di

import android.content.Context
import androidx.room.Room
import com.lensqi.fieldops.data.queue.PendingCommandDao
import com.lensqi.fieldops.data.queue.QueueDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): QueueDatabase =
        Room.databaseBuilder(context, QueueDatabase::class.java, "fieldops-queue.db").build()

    @Provides
    fun commands(db: QueueDatabase): PendingCommandDao = db.commands()
}
