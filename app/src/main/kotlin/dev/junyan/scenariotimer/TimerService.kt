package dev.junyan.scenariotimer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class TimerService : Service() {

    companion object {
        const val TAG = "SleepTimer"
        const val ACTION_START = "dev.junyan.scenariotimer.START"
        const val ACTION_PAUSE = "dev.junyan.scenariotimer.PAUSE"
        const val ACTION_RESUME = "dev.junyan.scenariotimer.RESUME"
        const val ACTION_CANCEL = "dev.junyan.scenariotimer.CANCEL"
        const val ACTION_STOP_RINGTONE = "dev.junyan.scenariotimer.STOP_RINGTONE"
        const val ACTION_ALARM_FIRED = "dev.junyan.scenariotimer.ALARM_FIRED"

        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_FINISH_ACTION = "finishAction"
        const val EXTRA_SCENE_NAME = "sceneName"
        const val EXTRA_TIMER_ID = "timerId"
        const val EXTRA_SCENE_INDEX = "sceneIndex"

        const val BROADCAST_ACTION = "dev.junyan.scenariotimer.TIMER_UPDATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_REMAINING = "remaining"
        const val STATE_TICK = "tick"
        const val STATE_FINISHED = "finished"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_PAUSED = "paused"
        const val STATE_RESUMED = "resumed"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "sleep_timer_channel"

        @Volatile
        var timers: Map<Int, TimerEntry> = emptyMap()
            private set

        fun hasRunningTimers(): Boolean = timers.values.any { it.isRunning || it.isPaused }

        fun formatTime(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerMap = mutableMapOf<Int, TimerEntry>()
    private val tickRunnables = mutableMapOf<Int, Runnable>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val ringtoneStopHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRingtoneRunnable = Runnable { stopRingtone() }
    private var nextTimerId = 1
    private var lastNotificationText: String = ""

    private fun publishSnapshot() {
        timers = timerMap.toMap()
    }

    private fun sendStateBroadcast(state: String, timerId: Int, sceneIndex: Int, remaining: Long = 0) {
        sendBroadcast(Intent(BROADCAST_ACTION).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_TIMER_ID, timerId)
            putExtra(EXTRA_SCENE_INDEX, sceneIndex)
            putExtra(EXTRA_REMAINING, remaining)
            setPackage(packageName)
        })
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
                if (seconds <= 0) {
                    maybeStop()
                    return START_NOT_STICKY
                }
                val sceneName = intent.getStringExtra(EXTRA_SCENE_NAME) ?: "定时器"
                val sceneIndex = intent.getIntExtra(EXTRA_SCENE_INDEX, -1)
                val finishAction = intent.getStringExtra(EXTRA_FINISH_ACTION) ?: "mute"
                startTimer(sceneName, sceneIndex, seconds.toLong(), finishAction)
            }
            ACTION_PAUSE -> {
                val timerId = intent.getIntExtra(EXTRA_TIMER_ID, -1)
                pauseTimer(timerId)
            }
            ACTION_RESUME -> {
                val timerId = intent.getIntExtra(EXTRA_TIMER_ID, -1)
                resumeTimer(timerId)
            }
            ACTION_CANCEL -> {
                val timerId = intent.getIntExtra(EXTRA_TIMER_ID, -1)
                cancelTimer(timerId)
            }
            ACTION_STOP_RINGTONE -> {
                stopRingtone()
            }
            ACTION_ALARM_FIRED -> {
                val timerId = intent.getIntExtra(EXTRA_TIMER_ID, -1)
                onTimerFinished(timerId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTimer(sceneName: String, sceneIndex: Int, totalSeconds: Long, finishAction: String) {
        val id = nextTimerId++
        val now = System.currentTimeMillis()
        val entry = TimerEntry(
            id = id,
            sceneIndex = sceneIndex,
            sceneName = sceneName,
            totalSeconds = totalSeconds,
            remainingSeconds = totalSeconds,
            endTimeMillis = now + totalSeconds * 1000,
            isRunning = true,
            finishAction = finishAction,
        )
        timerMap[id] = entry
        publishSnapshot()

        acquireWakeLockIfNeeded()
        startForegroundIfNeeded()
        scheduleAlarm(id, entry.endTimeMillis)
        startTick(id)

        Log.i(TAG, "Timer $id started: ${sceneName} ${totalSeconds}s")
    }

    private fun pauseTimer(timerId: Int) {
        val entry = timerMap[timerId] ?: return
        if (!entry.isRunning) return

        val now = System.currentTimeMillis()
        entry.remainingSeconds = ((entry.endTimeMillis - now) / 1000).coerceAtLeast(0)
        entry.isRunning = false
        entry.isPaused = true

        stopTick(timerId)
        cancelAlarm(timerId)
        publishSnapshot()
        updateNotification()
        sendStateBroadcast(STATE_PAUSED, timerId, entry.sceneIndex, entry.remainingSeconds)
        Log.i(TAG, "Timer $timerId paused at ${entry.remainingSeconds}s")
    }

    private fun resumeTimer(timerId: Int) {
        val entry = timerMap[timerId] ?: return
        if (!entry.isPaused) return

        entry.endTimeMillis = System.currentTimeMillis() + entry.remainingSeconds * 1000
        entry.isRunning = true
        entry.isPaused = false

        scheduleAlarm(timerId, entry.endTimeMillis)
        startTick(timerId)
        publishSnapshot()
        updateNotification()
        sendStateBroadcast(STATE_RESUMED, timerId, entry.sceneIndex, entry.remainingSeconds)
        Log.i(TAG, "Timer $timerId resumed at ${entry.remainingSeconds}s")
    }

    private fun cancelTimer(timerId: Int) {
        val entry = timerMap[timerId] ?: return
        stopTick(timerId)
        cancelAlarm(timerId)
        timerMap.remove(timerId)
        publishSnapshot()

        sendStateBroadcast(STATE_CANCELLED, timerId, entry.sceneIndex)
        updateNotification()
        maybeStop()
        Log.i(TAG, "Timer $timerId cancelled")
    }

    private fun onTimerFinished(timerId: Int) {
        val entry = timerMap[timerId] ?: return
        if (entry.isPaused) return

        stopTick(timerId)
        cancelAlarm(timerId)
        entry.isRunning = false
        entry.isFinished = true
        entry.remainingSeconds = 0

        if (entry.finishAction == "ringtone") {
            playRingtone()
        } else {
            muteMedia()
        }

        publishSnapshot()
        updateNotification()
        sendStateBroadcast(STATE_FINISHED, timerId, entry.sceneIndex)

        // 延迟移除已完成的计时器，让 UI 有时间显示完成状态
        handler.postDelayed({
            timerMap.remove(timerId)
            publishSnapshot()
            updateNotification()
            maybeStop()
        }, 3000)

        Log.i(TAG, "Timer $timerId finished")
    }

    private fun startTick(timerId: Int) {
        val entry = timerMap[timerId] ?: return
        val runnable = object : Runnable {
            override fun run() {
                val e = timerMap[timerId] ?: return
                val now = System.currentTimeMillis()
                e.remainingSeconds = ((e.endTimeMillis - now) / 1000).coerceAtLeast(0)

                if (e.remainingSeconds <= 0) {
                    onTimerFinished(timerId)
                } else {
                    updateNotification()
                    sendStateBroadcast(STATE_TICK, timerId, e.sceneIndex, e.remainingSeconds)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        tickRunnables[timerId] = runnable
        handler.post(runnable)
    }

    private fun stopTick(timerId: Int) {
        tickRunnables.remove(timerId)?.let { handler.removeCallbacks(it) }
    }

    private fun maybeStop() {
        if (timerMap.isEmpty()) {
            handler.postDelayed({
                // 铃声还在响时不能停服务，否则铃声被掐断；
                // 服务由 stopRingtone() 收尾时再尝试停止
                if (timerMap.isEmpty() && mediaPlayer?.isPlaying != true) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, 2000)
        }
    }

    // ── WakeLock ────────────────────────────────────────

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTimer::WakeLock").apply {
            acquire(24 * 60 * 60 * 1000) // 24h safety timeout
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Foreground ──────────────────────────────────────

    private fun startForegroundIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    // ── Notification ────────────────────────────────────

    private fun buildNotification(): android.app.Notification {
        val running = timerMap.values.filter { it.isRunning }
        val paused = timerMap.values.filter { it.isPaused }

        val title = when {
            running.size > 1 -> "${running.size} 个定时器运行中"
            running.size == 1 -> getString(R.string.timer_running)
            paused.isNotEmpty() -> "定时器已暂停"
            else -> getString(R.string.timer_running)
        }

        val minRemaining = running.minOfOrNull { it.remainingSeconds } ?: 0L
        val content = if (running.isNotEmpty()) {
            getString(R.string.timer_remaining, formatTime(minRemaining))
        } else {
            "${paused.size} 个定时器已暂停"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (timerMap.isNotEmpty()) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ── AlarmManager ────────────────────────────────────

    private fun getAlarmPendingIntent(timerId: Int): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply {
            action = ACTION_ALARM_FIRED
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        return PendingIntent.getService(
            this, timerId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm(timerId: Int, triggerTime: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, getAlarmPendingIntent(timerId))
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not allowed: ${e.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, getAlarmPendingIntent(timerId))
        }
    }

    private fun cancelAlarm(timerId: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getAlarmPendingIntent(timerId))
    }

    // ── Media ───────────────────────────────────────────

    private fun muteMedia() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        Log.i(TAG, "Media volume muted")
    }

    private fun playRingtone() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                ?: run { Log.w(TAG, "No alarm URI available"); return }
            mediaPlayer = android.media.MediaPlayer().apply {
                setAudioStreamType(android.media.AudioManager.STREAM_ALARM)
                setDataSource(this@TimerService, uri)
                isLooping = true
                prepare()
                start()
            }
            // 循环响 1 分钟后自动停止，避免无人处理时一直响
            ringtoneStopHandler.removeCallbacks(stopRingtoneRunnable)
            ringtoneStopHandler.postDelayed(stopRingtoneRunnable, 60_000L)
        } catch (e: Exception) {
            Log.e(TAG, "playRingtone failed: ${e.message}", e)
        }
    }

    private fun stopRingtone() {
        ringtoneStopHandler.removeCallbacks(stopRingtoneRunnable)
        val mp = mediaPlayer
        mediaPlayer = null
        try {
            mp?.apply { if (isPlaying) stop(); release() }
        } catch (_: IllegalStateException) {}
        Log.i(TAG, "Ringtone stopped")
        // 铃声结束后（手动或 1 分钟自动）再尝试停服务
        maybeStop()
    }

    override fun onDestroy() {
        ringtoneStopHandler.removeCallbacks(stopRingtoneRunnable)
        timerMap.keys.toList().forEach { stopTick(it) }
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
        releaseWakeLock()
        timerMap.clear()
        publishSnapshot()
        super.onDestroy()
    }
}
