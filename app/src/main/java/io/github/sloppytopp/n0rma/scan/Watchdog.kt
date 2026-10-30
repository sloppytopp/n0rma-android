package io.github.sloppytopp.n0rma.scan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.sloppytopp.n0rma.MainActivity
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.detect.ScanWatch
import io.github.sloppytopp.n0rma.detect.WatchAction

/**
 * A second, independent trigger for when the system kills the scan service. A low-power "allow while idle" alarm (no special permission,
 * unlike exact alarms) wakes this up about every 15 minutes while scanning is wanted. If scanning has died it tries to restart it; on
 * Android 12+ a background app is usually not allowed to start a foreground service, so it then tells the user instead of failing silently.
 */
object Watchdog {
    private const val CH = "watchdog"
    private const val NOTE_ID = 4242
    private const val REQ = 4243

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, WatchdogReceiver::class.java).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ScanWatch.WATCHDOG_EVERY_MS, pending(ctx))
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
    }

    fun clearNotice(ctx: Context) = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)

    fun check(ctx: Context) {
        when (ScanWatch.watchdogAction(Prefs.scanWanted, Monitor.snapshot.running)) {
            WatchAction.STOP_WATCHING -> cancel(ctx)
            WatchAction.KEEP_WATCHING -> schedule(ctx)
            WatchAction.RECOVER -> { recover(ctx); schedule(ctx) }
        }
    }

    /** Try to start scanning again; if Android refuses (background start limits), post a notice that opens the app. */
    fun recover(ctx: Context) {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, ScanService::class.java)) } catch (t: Throwable) { notifyStopped(ctx) }
    }

    private fun notifyStopped(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Scanning stopped", NotificationManager.IMPORTANCE_HIGH))
        val (title, text) = ScanWatch.stoppedNotice(Prefs.discreet)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        runCatching {
            nm.notify(NOTE_ID, b.setSmallIcon(if (Prefs.discreet) android.R.drawable.stat_notify_sync_noanim else android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open)
                .setVisibility(if (Prefs.discreet) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE).build())
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Watchdog.check(ctx)
}

/** Only acts when the user turned on "Resume scanning after the phone restarts" and scanning was on before the restart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ScanWatch.resumeOnBoot(Prefs.resumeAfterBoot, Prefs.scanWanted)) Watchdog.recover(ctx)
    }
}
