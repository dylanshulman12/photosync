package com.photosync.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Runs a sync in the background as a data-sync foreground service so it keeps
 * going if the user leaves the app, and shows live progress in a notification.
 *
 * Input data "mode": "push" | "pull" | "both"
 * Progress is published via setProgress so MainActivity can mirror it.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    // Required for the expedited foreground path: WorkManager asks for this
    // ForegroundInfo when it promotes the job on Android 11 and below.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground("Starting sync", 0, 0, true)

    override suspend fun doWork(): Result {
        val mode = inputData.getString("mode") ?: "push"
        val prefs = Prefs(applicationContext)
        if (!prefs.isConfigured) return Result.failure()

        // Promote to a foreground service so the sync survives the app being
        // backgrounded. The OS can refuse this (background-start limits and
        // data-sync FGS enforcement on Android 12+), so never let it abort or
        // crash the sync: if it's refused we just lose the progress notification.
        runCatching { setForeground(foreground("Starting sync", 0, 0, true)) }
        val engine = SyncEngine(applicationContext)

        // Preflight: confirm the server is reachable BEFORE spending minutes
        // hashing. If it isn't, surface the exact reason and stop instead of
        // hashing, failing, and being retried into an endless rescan loop.
        val pingErr = Api(prefs.serverUrl, prefs.apiKey).pingError()
        if (pingErr != null) {
            notifyDone("Can't reach ${prefs.serverUrl} — $pingErr")
            return Result.failure()
        }

        var lastReport = 0L
        val report: (SyncEngine.Progress) -> Unit = { p ->
            // Hashing ticks many times a second. Posting a notification + a
            // setProgress every tick floods NotificationService (it starts
            // shedding above ~5/sec) and churns the UI thread. Cap to ~2/sec,
            // but always let the terminal update through.
            val now = System.currentTimeMillis()
            val terminal = p.phase == "done" || (p.total > 0 && p.done >= p.total)
            if (terminal || now - lastReport >= 500) {
                lastReport = now
                val text = when (p.phase) {
                    "hashing" -> "Scanning ${p.done}/${p.total}"
                    "checking" -> "Comparing with server"
                    "uploading" -> "Uploading ${p.done}/${p.total}"
                    "downloading" -> "Downloading ${p.done}/${p.total}"
                    else -> "Finishing"
                }
                setProgressAsync(
                    workDataOf("phase" to p.phase, "done" to p.done, "total" to p.total, "label" to p.label)
                )
                notify(text, p.done, p.total, p.total == 0)
            }
        }

        // Keep the CPU and wifi radio awake for the whole sync. Without this,
        // once the screen goes off the device deep-sleeps the CPU (slow hashing)
        // and drops the wifi into power-save (slow uploads) — the night-and-day
        // difference between foreground and background. Both are released in the
        // finally below, and the wake lock has a safety timeout so it can never
        // leak and drain the battery.
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photosync:sync")
        val wifiMgr = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = wifiMgr?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "photosync:sync")
        wake.acquire(2 * 60 * 60 * 1000L)   // 2h safety cap
        wifi?.acquire()

        return try {
            var up = 0; var down = 0
            if (mode == "push" || mode == "both") up = engine.push(report)
            if (mode == "pull" || mode == "both") down = engine.pull(report)
            notifyDone("Sync complete: $up up, $down down")
            Result.success(workDataOf("uploaded" to up, "downloaded" to down))
        } catch (e: CancellationException) {
            // User pressed Stop (or the OS cancelled the work). Let it end as
            // CANCELLED with no retry; rethrow so WorkManager sees it.
            throw e
        } catch (e: Exception) {
            // A real error mid-sync: report it and let WorkManager retry with
            // backoff. The hash cache persists, so a retry resumes cheaply.
            notifyDone("Sync failed: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            Result.retry()
        } finally {
            if (wake.isHeld) wake.release()
            if (wifi?.isHeld == true) wifi.release()
        }
    }

    // ---- notifications -------------------------------------------------------

    private fun foreground(text: String, done: Int, total: Int, indeterminate: Boolean): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("PhotoSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(if (total > 0) total else 1, done, indeterminate)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun notify(text: String, done: Int, total: Int, indeterminate: Boolean) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("PhotoSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(if (total > 0) total else 1, done, indeterminate)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun notifyDone(text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("PhotoSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    companion object {
        const val CHANNEL = "sync"
        const val NOTIF_ID = 4201
        const val ONE_TIME = "photosync_once"
        const val PERIODIC = "photosync_periodic"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Sync progress",
                            NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        /** Kick off an immediate one-shot sync. mode = push | pull | both. */
        fun runNow(ctx: Context, mode: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("mode" to mode))
                // User-tapped sync: run it expedited. WorkManager then owns the
                // foreground promotion and falls back to a normal job if the OS
                // refuses, instead of throwing on the start.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, req)
        }

        /** (Re)schedule the periodic backup from prefs, or cancel it. */
        fun reschedule(ctx: Context) {
            val prefs = Prefs(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.backupEnabled) {
                wm.cancelUniqueWork(PERIODIC); return
            }
            val days = when (prefs.backupInterval) {
                "weekly" -> 7L; "monthly" -> 30L; else -> 1L
            }
            val mode = if (prefs.backupPulls) "both" else "push"
            val req = PeriodicWorkRequestBuilder<SyncWorker>(days, TimeUnit.DAYS)
                .setInputData(workDataOf("mode" to mode))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
