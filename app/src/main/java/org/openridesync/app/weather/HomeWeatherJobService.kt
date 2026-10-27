package org.openridesync.app.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import org.openridesync.app.OpenRideSyncApplication

internal object HomeWeatherJobs {
    const val ID = 0x4857
    const val INTERVAL = 60 * 60 * 1000L
    fun update(context: Context, enabled: Boolean): Boolean { return try {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        if (!enabled) { scheduler.cancel(ID); return true }
        // Rescheduling the same ID stops a running job and restarts its periodic window.
        // Do not do this on every Home start or process restoration.
        val target = ComponentName(context, HomeWeatherJobService::class.java)
        val existing = scheduler.getPendingJob(ID)
        if (existing?.service == target && existing.isPeriodic && existing.intervalMillis == INTERVAL &&
            existing.networkType == JobInfo.NETWORK_TYPE_ANY && existing.isPersisted) return true
        scheduler.schedule(JobInfo.Builder(ID, target).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS
    } catch (_: RuntimeException) { false } }
}

/** JobScheduler controls the window; no service/location foreground promotion is needed. */
class HomeWeatherJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var active: JobParameters? = null
    private var revision = 0L
    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        active = params
        val ticket = ++revision
        running = scope.launch(start = CoroutineStart.LAZY) {
            val retry = try { (application as OpenRideSyncApplication).homeWeather.runBackground() }
            catch (_: CancellationException) {
                // Auth/disable can cancel the inner request while the system job is still active.
                // An actual onStopJob already removed active, so it never receives jobFinished.
                if (active === params && revision == ticket) { active = null; running = null; jobFinished(params, false) }
                return@launch
            }
            catch (_: Exception) { true }
            if (active === params && revision == ticket && isActive) {
                active = null
                running = null
                jobFinished(params, retry)
            }
        }
        running?.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) { revision++; active = null; running?.cancel(); running = null }
        return true // Retry in a later system window, not with a new location request.
    }
    override fun onDestroy() { revision++; active = null; scope.cancel(); super.onDestroy() }
}
