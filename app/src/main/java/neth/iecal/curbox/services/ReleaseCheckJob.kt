package neth.iecal.curbox.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.reader.ReleaseChecker

/** Checks the Reading tab's series for new chapters every few hours, when online. */
class ReleaseCheckJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        worker = scope.launch {
            try {
                ReleaseChecker.checkAll(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            } finally {
                if (currentCoroutineContext().isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.cancel()
        worker = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC_JOB_ID = 7720
        private const val NOW_JOB_ID = 7721

        /** Schedules the regular check every [hours]; 0 turns it off. Leaves a matching job alone. */
        fun ensureScheduled(context: Context, hours: Int) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (hours <= 0) {
                scheduler.cancel(PERIODIC_JOB_ID)
                return
            }
            val intervalMs = hours * 60 * 60_000L
            if (scheduler.getPendingJob(PERIODIC_JOB_ID)?.intervalMillis == intervalMs) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, ReleaseCheckJob::class.java))
                    .setPeriodic(intervalMs)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Runs one check as soon as the phone is online. */
        fun checkNow(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(
                JobInfo.Builder(NOW_JOB_ID, ComponentName(context, ReleaseCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        }
    }
}
