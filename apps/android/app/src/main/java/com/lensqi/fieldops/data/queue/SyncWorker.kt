package com.lensqi.fieldops.data.queue

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Drains the command queue when the network is available; retries with backoff while the API is unreachable. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: CommandQueue,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (queue.drain()) {
        DrainResult.Empty -> Result.success()
        DrainResult.Offline -> Result.retry()
    }
}
