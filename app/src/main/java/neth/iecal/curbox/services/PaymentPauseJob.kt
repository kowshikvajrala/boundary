package neth.iecal.curbox.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import neth.iecal.curbox.utils.PaymentPause

/** Switches the blocker back on when a payment pause runs out. */
class PaymentPauseJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            PaymentPause.endIfDue(applicationContext)
            // Woken a little early: try again at the real end.
            if (PaymentPause.isActive(applicationContext)) {
                schedule(applicationContext, PaymentPause.endsAt(applicationContext))
            }
        } catch (e: Exception) {
            Log.e("PaymentPauseJob", "could not end the pause", e)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 7715

        fun schedule(context: Context, endsAtMs: Long?) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            endsAtMs ?: return
            val delayMs = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(appContext, PaymentPauseJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
