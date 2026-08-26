package uts.sdk.modules.smartReminderKeepalive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import io.dcloud.uts.UTSAndroid
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 方案B：Android 前台服务保活
 * 由 index.uts 调用，负责：
 * 1. 启动常驻前台服务（带常驻通知），降低进程被系统杀掉的概率
 * 2. 用精确闹钟在提醒时间唤醒 CPU（息屏/后台也能触发）
 * 3. 到点后由服务内原生 TextToSpeech 持续播报，每 10 秒一次，直到用户确认
 */
object SmartReminderKeepalive {

    const val ACTION_START = "uts.sdk.modules.smartReminderKeepalive.START"
    const val ACTION_UPDATE = "uts.sdk.modules.smartReminderKeepalive.UPDATE"
    const val ACTION_FIRE = "uts.sdk.modules.smartReminderKeepalive.FIRE"
    const val ACTION_STOP_SPEECH = "uts.sdk.modules.smartReminderKeepalive.STOP_SPEECH"
    const val ACTION_STOP_ALL = "uts.sdk.modules.smartReminderKeepalive.STOP_ALL"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_TIME = "time"
    const val EXTRA_CONTENT = "content"

    const val CHANNEL_ID = "smart_reminder_keepalive"
    const val NOTIFICATION_ID = 1001
    const val ACTION_RESTART = "uts.sdk.modules.smartReminderKeepalive.RESTART"

    private const val PREFS_NAME = "smart_reminder_keepalive"
    private const val PREFS_HAS_CONFIG = "has_config"
    private const val PREFS_ENABLED = "enabled"
    private const val PREFS_TIME = "time"
    private const val PREFS_CONTENT = "content"

    // 当前状态：idle | speaking | stopped
    @Volatile
    var currentState: String = "idle"

    // 是否正在持续播报
    @Volatile
    var speaking: Boolean = false

    // 最近一次触发提醒的日期（yyyy-MM-dd），保证同一天只触发一次
    @Volatile
    var lastFiredDate: String = ""

    // 当前配置（进程被系统重建后仍可恢复）
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var time: String = ""

    @Volatile
    var content: String = ""

    // 状态变化回调（由 index.uts 注册，通知 JS 侧“开始播报/已确认停止”）
    @Volatile
    var stateChangeCallback: ((String) -> Unit)? = null

    fun setStateCallback(callback: (String) -> Unit) {
        stateChangeCallback = callback
    }

    fun emitState(newState: String) {
        currentState = newState
        if (newState == "speaking") {
            speaking = true
        } else if (newState == "stopped") {
            speaking = false
        }
        val cb = stateChangeCallback
        if (cb != null) {
            Handler(Looper.getMainLooper()).post { cb(newState) }
        }
    }

    /** 启动保活服务并写入提醒配置 */
    fun start(enabledParam: Boolean, timeParam: String, contentParam: String): Boolean {
        enabled = enabledParam
        time = timeParam
        content = contentParam
        val context = UTSAndroid.getAppContext() ?: return false
        val intent = Intent(context, SmartReminderService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_ENABLED, enabled)
            .putExtra(EXTRA_TIME, time)
            .putExtra(EXTRA_CONTENT, content)
        saveConfig(context)
        return startServiceCompat(context, intent)
    }

    /** 更新提醒配置（服务不中断） */
    fun update(enabledParam: Boolean, timeParam: String, contentParam: String): Boolean {
        enabled = enabledParam
        time = timeParam
        content = contentParam
        val context = UTSAndroid.getAppContext() ?: return false
        val intent = Intent(context, SmartReminderService::class.java)
            .setAction(ACTION_UPDATE)
            .putExtra(EXTRA_ENABLED, enabled)
            .putExtra(EXTRA_TIME, time)
            .putExtra(EXTRA_CONTENT, content)
        saveConfig(context)
        return startServiceCompat(context, intent)
    }

    /** 停止当前播报（用户点击“确认已收到”），保活服务继续运行 */
    fun stopSpeech(): Boolean {
        val context = UTSAndroid.getAppContext() ?: return false
        val intent = Intent(context, SmartReminderService::class.java).setAction(ACTION_STOP_SPEECH)
        return startServiceCompat(context, intent)
    }

    /** 完全停止保活服务（切换角色/不再需要时） */
    fun stopAll(): Boolean {
        val context = UTSAndroid.getAppContext() ?: return false
        clearConfig(context)
        val intent = Intent(context, SmartReminderService::class.java).setAction(ACTION_STOP_ALL)
        return startServiceCompat(context, intent)
    }

    fun isSpeaking(): Boolean = speaking

    fun getState(): String = currentState

    /** 把当前配置写入本地（进程被杀/开机后仍可恢复） */
    fun saveConfig(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(PREFS_HAS_CONFIG, true)
                .putBoolean(PREFS_ENABLED, enabled)
                .putString(PREFS_TIME, time)
                .putString(PREFS_CONTENT, content)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 读取本地配置；返回是否存在有效配置 */
    fun loadConfig(context: Context): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!sp.getBoolean(PREFS_HAS_CONFIG, false)) {
                return false
            }
            enabled = sp.getBoolean(PREFS_ENABLED, false)
            time = sp.getString(PREFS_TIME, "") ?: ""
            content = sp.getString(PREFS_CONTENT, "") ?: ""
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 清理本地配置（切换角色/不再需要保活时） */
    fun clearConfig(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startServiceCompat(context: Context, intent: Intent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

/**
 * 常驻前台服务：持有常驻通知、调度闹钟、触发原生 TTS 持续播报
 */
class SmartReminderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRepeat: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SmartReminderKeepalive.ACTION_START,
            SmartReminderKeepalive.ACTION_UPDATE -> {
                if (intent != null) {
                    val newEnabled = intent.getBooleanExtra(
                        SmartReminderKeepalive.EXTRA_ENABLED,
                        SmartReminderKeepalive.enabled
                    )
                    val newTime = intent.getStringExtra(SmartReminderKeepalive.EXTRA_TIME)
                        ?: SmartReminderKeepalive.time
                    val newContent = intent.getStringExtra(SmartReminderKeepalive.EXTRA_CONTENT)
                        ?: SmartReminderKeepalive.content
                    val changed = newEnabled != SmartReminderKeepalive.enabled ||
                        newTime != SmartReminderKeepalive.time ||
                        newContent != SmartReminderKeepalive.content
                    SmartReminderKeepalive.enabled = newEnabled
                    SmartReminderKeepalive.time = newTime
                    SmartReminderKeepalive.content = newContent
                    // 配置真正变化时才停止当前播报；重复同步不打断
                    if (changed) {
                        stopSpeechInternal(emitStopped = true)
                    }
                }
                startForegroundCompat()
                updateNotificationText()
                scheduleNextFire()
            }
            SmartReminderKeepalive.ACTION_FIRE -> fire()
            SmartReminderKeepalive.ACTION_RESTART -> {
                // 开机自启 / 划掉后自动复活：恢复持久化配置，重新进入前台并调度闹钟（不立即播报）
                SmartReminderKeepalive.loadConfig(applicationContext)
                startForegroundCompat()
                updateNotificationText()
                scheduleNextFire()
            }
            SmartReminderKeepalive.ACTION_STOP_SPEECH -> {
                stopSpeechInternal(emitStopped = true)
                scheduleNextFire()
            }
            SmartReminderKeepalive.ACTION_STOP_ALL -> {
                stopSpeechInternal(emitStopped = true)
                cancelAlarm()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> {
                // 进程被系统重建后恢复：读取持久化配置，重新进入前台并恢复闹钟
                SmartReminderKeepalive.loadConfig(applicationContext)
                startForegroundCompat()
                updateNotificationText()
                scheduleNextFire()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSpeechInternal(emitStopped = false)
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tts = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉应用：先保存配置，再安排一个几秒后的“闹钟”尝试自动复活（尽力而为）
        try {
            SmartReminderKeepalive.saveConfig(applicationContext)
            if (!SmartReminderKeepalive.enabled) return
            val now = System.currentTimeMillis()
            if (now - lastTaskRemovedAt < 30_000L) {
                // 30 秒内连续被清 2 次则放弃，避免被系统判定为恶意自启
                return
            }
            lastTaskRemovedAt = now
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, ReminderReceiver::class.java)
                .setAction(SmartReminderKeepalive.ACTION_RESTART)
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(this, 1, restartIntent, flags)
            val alarmInfo = AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_000L, null)
            alarmManager.setAlarmClock(alarmInfo, pi)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        var lastTaskRemovedAt: Long = 0L
    }

    // ---------- 前台服务 ----------

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    SmartReminderKeepalive.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(SmartReminderKeepalive.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                SmartReminderKeepalive.CHANNEL_ID,
                "智能提醒服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持提醒服务后台运行，到点自动语音播报"
            nm.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, SmartReminderKeepalive.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("智能提醒服务运行中")
            .setContentText(currentSummary())
            .setOngoing(true)
            .setShowWhen(false)
        // 点击通知回到 App
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(this, 0, launchIntent, flags)
            builder.setContentIntent(pi)
        }
        return builder.build()
    }

    private fun updateNotificationText() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(SmartReminderKeepalive.NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun currentSummary(): String {
        return if (!SmartReminderKeepalive.enabled) {
            "今日提醒已关闭"
        } else {
            val t = SmartReminderKeepalive.time.ifBlank { "08:00" }
            val c = SmartReminderKeepalive.content.ifBlank { "智能提醒" }
            "每天 $t：$c"
        }
    }

    // ---------- 闹钟调度 ----------

    private fun scheduleNextFire() {
        cancelAlarm()
        if (!SmartReminderKeepalive.enabled) return
        val parts = SmartReminderKeepalive.time.split(":")
        if (parts.size < 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return
        val triggerAt = nextTriggerMillis(hour, minute)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fireIntent = Intent(this, ReminderReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, 0, fireIntent, flags)
        try {
            when {
                Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms() -> {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Build.VERSION.SDK_INT >= 23 -> {
                    // 无精确闹钟权限时退化为“闹钟”类型（精确、免权限、可唤醒 Doze）
                    val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, null)
                    alarmManager.setAlarmClock(alarmInfo, pi)
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
        } catch (e: Exception) {
            // 兜底：模糊闹钟（1 分钟内）
            try {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun cancelAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val fireIntent = Intent(this, ReminderReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(this, 0, fireIntent, flags)
            alarmManager.cancel(pi)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- 触发播报 ----------

    private fun fire() {
        if (!SmartReminderKeepalive.enabled) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        // 同一天只触发一次（已确认过则不再重复触发）
        if (SmartReminderKeepalive.lastFiredDate == today && !SmartReminderKeepalive.speaking) {
            return
        }
        SmartReminderKeepalive.lastFiredDate = today
        startSpeech()
        // 顺手安排第二天的闹钟
        scheduleNextFire()
    }

    private fun startSpeech() {
        SmartReminderKeepalive.emitState("speaking")
        acquireWakeLock()
        ensureTtsReady()
    }

    private fun ensureTtsReady() {
        if (ttsReady) {
            speakLoop()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    try {
                        tts?.language = Locale.SIMPLIFIED_CHINESE
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (SmartReminderKeepalive.speaking) {
                        speakLoop()
                    }
                } else {
                    Toast.makeText(this, "语音引擎初始化失败，无法播报", Toast.LENGTH_SHORT).show()
                    SmartReminderKeepalive.emitState("stopped")
                }
            })
        }
    }

    private fun speakLoop() {
        val t = tts ?: return
        if (!ttsReady || !SmartReminderKeepalive.speaking) return
        val text = SmartReminderKeepalive.content.ifBlank { "该做正事啦" }
        try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smart-reminder")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        speechRepeat?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (SmartReminderKeepalive.speaking) {
                speakLoop()
            }
        }
        speechRepeat = runnable
        handler.postDelayed(runnable, 10_000L)
    }

    private fun stopSpeechInternal(emitStopped: Boolean) {
        SmartReminderKeepalive.speaking = false
        speechRepeat?.let { handler.removeCallbacks(it) }
        speechRepeat = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releaseWakeLock()
        if (emitStopped) {
            SmartReminderKeepalive.emitState("stopped")
        }
    }

    // ---------- 唤醒锁 ----------

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smart-reminder:speech")
            }
            wakeLock?.setReferenceCounted(false)
            // 持续播报期间最多持锁 2 小时，防止 CPU 休眠中断播报
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * 闹钟唤醒接收器：到点后把“触发播报”指令交给前台服务
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val serviceIntent = Intent(context, SmartReminderService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * 开机自启接收器：重启手机后自动恢复前台服务与闹钟（前提：应用未被“强行停止”过）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (!SmartReminderKeepalive.loadConfig(context)) return
            if (!SmartReminderKeepalive.enabled) return
            val serviceIntent = Intent(context, SmartReminderService::class.java)
                .setAction(SmartReminderKeepalive.ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}