package dev.quietube.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Keeps the process, CPU and Wi-Fi awake while the phone streams a video to a TV, with a notification to stop it. */
@AndroidEntryPoint
class StreamService : Service() {
    @Inject lateinit var caster: TvCaster
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { caster.stopDirect(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on TV", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing on ${intent?.getStringExtra(EXTRA_TV) ?: "TV"}")
            .setContentText("This phone is streaming the video. Keep it on the same Wi-Fi.")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouPoop:tv-stream").apply { acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION") // The low-latency mode needs the screen on; high-perf is right for a background stream.
        if (wifi == null) wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YouPoop:tv-stream").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release(); wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tv_stream"
        private const val NOTIFICATION = 7
        private const val ACTION_STOP = "dev.quietube.cast.STOP"
        private const val EXTRA_TV = "tv"
        fun start(context: Context, tv: String) =
            context.startForegroundService(Intent(context, StreamService::class.java).putExtra(EXTRA_TV, tv))
        fun stop(context: Context) = context.stopService(Intent(context, StreamService::class.java))
    }
}
