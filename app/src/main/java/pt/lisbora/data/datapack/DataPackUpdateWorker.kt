package pt.lisbora.data.datapack

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** The daily check for newer data: only on Wi-Fi (an unmetered network), with battery and storage to spare. */
class DataPackUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = EntryPointAccessors.fromApplication(applicationContext, UpdaterEntryPoint::class.java).dataPackUpdater()
        val status = updater.update()
        Log.i(TAG, "Data update check: $status")
        return when (status) {
            is DataUpdateStatus.Failed ->
                if (status.reason != DataUpdateStatus.Failed.Reason.NO_SPACE && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdaterEntryPoint {
        fun dataPackUpdater(): DataPackUpdater
    }

    companion object {
        const val WORK_NAME = "datapack-update"
        private const val TAG = "LisBoraData"
        private const val MAX_ATTEMPTS = 3

        /** Keeps the daily check scheduled; an existing schedule is left as it is. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<DataPackUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
