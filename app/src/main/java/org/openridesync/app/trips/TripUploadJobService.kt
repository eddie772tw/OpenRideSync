package org.openridesync.app.trips

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.diagnostics.DiagnosticJournal.*

internal object TripUploadJobs {
    const val ID = 0x5452
    const val INTERVAL = 15 * 60 * 1000L

    /** One account-free system trigger. Only the current signed-in account may supply rows. */
    fun update(context: Context, enabled: Boolean): Boolean = try {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler == null) false else if (!enabled) { scheduler.cancel(ID); true } else {
            val target = ComponentName(context, TripUploadJobService::class.java)
            val existing = scheduler.getPendingJob(ID)
            if (existing?.service == target && existing.isPeriodic && existing.intervalMillis == INTERVAL &&
                existing.networkType == JobInfo.NETWORK_TYPE_ANY && existing.isPersisted) true else {
                scheduler.schedule(JobInfo.Builder(ID, target).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(INTERVAL).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS
            }
        }
    } catch (_: RuntimeException) { false }
}

/** A bounded system execution window; never promotes a foreground service or obtains location. */
class TripUploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        active = params
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { (application as OpenRideSyncApplication).tripRecording.runBackground() }
            catch (_: CancellationException) { /* Identity, opt-out or system stop owns cancellation. */ }
            catch (_: Exception) {
                (application as OpenRideSyncApplication).journal?.record(
                    Component.TRIP, null, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
            }
            finally {
                // onStopJob already releases the system window. A late sender cleanup must not
                // finish it again, or finish a different run that reused this service instance.
                if (active === params && currentCoroutineContext().job === running) {
                    active = null
                    running = null
                    jobFinished(params, false)
                }
            }
        }
        running = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) { active = null; running?.cancel(); running = null }
        return true // A later window still rejects an ambiguous in-flight marker.
    }

    override fun onDestroy() { active = null; scope.cancel(); super.onDestroy() }
}
