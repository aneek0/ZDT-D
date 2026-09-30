package com.android.zdtd.service.diagnostics.blockcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.android.zdtd.service.MainActivity
import com.android.zdtd.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a strategy scan runs and surfaces its progress
 * outside the app.
 *
 * The scan itself is owned by [BlockcheckController] (process scope), so this
 * service is only a lifecycle/anchor holder: without it Android would be free
 * to kill the process — and with it the `su` child running the tester — as soon
 * as the UI goes away. The ongoing notification is also how the user checks
 * progress and stops the run while the app is in the background.
 */
class BlockcheckScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // This intent arrives via getForegroundService(); satisfy the
            // startForeground deadline even if the service was not already
            // foregrounded, then tear the scan down.
            startInForeground(buildNotification())
            BlockcheckController.stop()
            return START_NOT_STICKY
        }
        startInForeground(buildNotification())
        observeProgress()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        progressJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (foregroundStarted) return
        // Android 12+: startForeground may throw when the FGS allowance was
        // revoked mid-run; the scan must keep going, only the UI affordance is
        // lost, so a failure here is not fatal for the run.
        runCatching { startForeground(NOTIFICATION_ID, notification) }
        foregroundStarted = true
    }

    private fun observeProgress() {
        if (progressJob != null) return
        progressJob = scope.launch {
            BlockcheckStore.state.collectLatest { session ->
                if (!canPostNotifications()) return@collectLatest
                val manager = NotificationManagerCompat.from(this@BlockcheckScanService)
                runCatching { manager.notify(NOTIFICATION_ID, buildNotification(session)) }
            }
        }
    }

    /** Android 13+: without POST_NOTIFICATIONS the ongoing card is invisible. */
    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(session: BlockcheckSession = BlockcheckStore.state.value): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_BLOCKCHECK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, REQ_OPEN, openIntent, piFlags)
        // Background-start restrictions: a plain service PendingIntent is denied
        // while the app is backgrounded, so the notification Stop must use the
        // foreground-service variant.
        val stopPi = PendingIntent.getForegroundService(this, REQ_STOP, stopIntent(this), piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(getString(R.string.blockcheck_notification_title))
            .setContentText(progressText(session))
            .setContentIntent(contentPi)
            .addAction(0, getString(R.string.blockcheck_stop), stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun progressText(session: BlockcheckSession): String = when {
        session.phase == "network" -> getString(R.string.blockcheck_phase_network)
        session.phase == "baseline" -> getString(R.string.blockcheck_baseline)
        session.phase == "pass_control" -> getString(R.string.blockcheck_phase_pass_control)
        session.phase == "strategies" && session.currentStrategyIndex >= 0 && session.totalStrategies > 0 ->
            getString(R.string.blockcheck_testing_fmt, session.currentStrategyIndex + 1, session.totalStrategies)
        else -> getString(R.string.blockcheck_starting)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.blockcheck_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "blockcheck_scan"
        private const val NOTIFICATION_ID = 9025
        private const val REQ_OPEN = 9026
        private const val REQ_STOP = 9027

        const val ACTION_OPEN_BLOCKCHECK = "com.android.zdtd.service.action.BLOCKCHECK_OPEN"
        const val ACTION_STOP = "com.android.zdtd.service.action.BLOCKCHECK_STOP"

        fun start(context: Context) {
            val intent = Intent(context, BlockcheckScanService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Requests service teardown; called when the scan ends or is stopped. */
        fun stop(context: Context?) {
            val ctx = context ?: return
            ctx.stopService(Intent(ctx, BlockcheckScanService::class.java))
        }

        private fun stopIntent(context: Context): Intent =
            Intent(context, BlockcheckScanService::class.java).setAction(ACTION_STOP)
    }
}