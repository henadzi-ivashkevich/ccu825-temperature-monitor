package com.example.ccu825.worker

import android.content.Context
import androidx.work.*
import com.example.ccu825.data.CcuApiClient
import com.example.ccu825.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CcuFetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ccu825", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        if (endpoint.isBlank()) {
            return Result.failure()
        }

        return try {
            val api = CcuApiClient()
            val fetched = withContext(Dispatchers.IO) {
                api.fetchReadings(endpoint = endpoint, username = username, password = password)
            }
            if (fetched.isNotEmpty()) {
                val repository = LocalRepository(applicationContext)
                repository.appendReadings(fetched)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ccu825_periodic_fetch"

        fun scheduleOrCancel(context: Context, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            if (intervalMinutes <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
            } else {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                // Minimum interval supported by WorkManager is 15 minutes
                val validInterval = intervalMinutes.toLong().coerceAtLeast(15L)

                val request = PeriodicWorkRequestBuilder<CcuFetchWorker>(validInterval, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }
    }
}
