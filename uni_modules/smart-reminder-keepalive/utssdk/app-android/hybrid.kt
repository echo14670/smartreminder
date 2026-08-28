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
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import android.util.Log
import io.dcloud.uts.UTSAndroid
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 单条提醒：id 唯一，times 为每天多个触发时间
data class Reminder(
    val id: String,
    val enabled: Boolean,
    val times: List<String>,
    val content: String
)

/**
 * 方案B：Android 前台服务保活（多提醒版）
 * 由 index.uts 调用，负责：
 * 1. 启动常驻前台服务（带常驻通知），降低进程被系统杀掉的概率
 * 2. 用精确闹钟在【每一个提醒时间】唤醒 CPU（息屏/后台也能触发）
 * 3. 到点后由服务内原生 TextToSpeech 持续播报，每 10 秒一次，直到用户确认
 *
 * 配置格式（configJson）：
 * {
 *   "enabled": true,
 *   "reminders": [
 *     {"id":"r1","enabled":true,"times":["08:00","20:00"],"content":"喝水"}
 *   ]
 * }
 */
object SmartReminderKeepalive {

    const val ACTION_START = "uts.sdk.modules.smartReminderKeepalive.START"
    const val ACTION_UPDATE = "uts.sdk.modules.smartReminderKeepalive.UPDATE"
    const val ACTION_FIRE = "uts.sdk.modules.smartReminderKeepalive.FIRE"
    const val ACTION_STOP_SPEECH = "uts.sdk.modules.smartReminderKeepalive.STOP_SPEECH"
    const val ACTION_STOP_ALL = "uts.sdk.modules.smartReminderKeepalive.STOP_ALL"

    const val EXTRA_CONFIG = "config"
    const val EXTRA_FIRE_SLOT = "fire_slot"
    const val EXTRA_TIME = "time"
    const val EXTRA_CONTENT = "content"

    const val CHANNEL_ID = "smart_reminder_keepalive"
    const val NOTIFICATION_ID = 1001
    const val ALARM_CHANNEL_ID = "smart_reminder_alarm"
    const val ALARM_NOTIFICATION_ID = 1002
    const val ACTION_RESTART = "uts.sdk.modules.smartReminderKeepalive.RESTART"

    // 供 Service 层读取的常量（公开）
    const val PREFS_NAME = "smart_reminder_keepalive"
    const val PREFS_SCHEDULED_CODES = "scheduled_codes"
    private const val PREFS_PENDING_RECORDS = "pending_records"
    private const val PENDING_MAX = 200

    private const val PREFS_HAS_CONFIG = "has_config"
    private const val PREFS_MASTER_ENABLED = "master_enabled"
    private const val PREFS_REMINDERS = "reminders"

    // 当前状态：idle | speaking | stopped
    @Volatile
    var currentState: String = "idle"

    // 是否正在持续播报
    @Volatile
    var speaking: Boolean = false

    // 总开关
    @Volatile
    var masterEnabled: Boolean = false

    // 当前配置原文（用于比较是否变化）
    @Volatile
    var remindersJson: String = "[]"

    // 解析后的提醒列表
    @Volatile
    var remindersList: List<Reminder> = emptyList()

    // 当前正在播报的内容
    @Volatile
    var currentContent: String = "该做正事啦"

    // 已触发过的“槽位”（slotKey -> yyyy-MM-dd），防止同一天重复播报
    @Volatile
    var firedSlots: MutableMap<String, String> = HashMap()

    // 状态变化回调（由 index.uts 注册）
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

    /** 从 JSON 解析配置 */
    fun setConfigFromJson(json: String, resetSlots: Boolean = true) {
        try {
            val root = JSONObject(json)
            masterEnabled = root.optBoolean("enabled", false)
            remindersJson = json
            val arr = root.optJSONArray("reminders") ?: JSONArray()
            val list = ArrayList<Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val enabled = o.optBoolean("enabled", false)
                val times = ArrayList<String>()
                val tArr = o.optJSONArray("times") ?: JSONArray()
                for (j in 0 until tArr.length()) {
                    val t = tArr.optString(j, "")
                    if (t.isNotBlank()) times.add(t)
                }
                if (times.isEmpty()) continue
                list.add(
                    Reminder(
                        id = o.optString("id", "r" + i),
                        enabled = enabled,
                        times = times,
                        content = o.optString("content", "")
                    )
                )
            }
            remindersList = list
            val first = list.firstOrNull { it.enabled }
            currentContent = first?.content?.ifBlank { "该做正事啦" } ?: "该做正事啦"
            if (resetSlots) firedSlots = HashMap()
        } catch (e: Exception) {
            e.printStackTrace()
            masterEnabled = false
            remindersJson = "[]"
            remindersList = emptyList()
        }
    }

    /** 启动保活服务并写入提醒配置（configJson 为多提醒 JSON 字符串） */
    fun start(configJson: String): Boolean {
        val context = UTSAndroid.getAppContext() ?: return false
        val intent = Intent(context, SmartReminderService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_CONFIG, configJson)
        return startServiceCompat(context, intent)
    }

    /** 更新提醒配置（服务不中断；配置未变化时不打断正在进行的播报） */
    fun update(configJson: String): Boolean {
        val context = UTSAndroid.getAppContext() ?: return false
        val intent = Intent(context, SmartReminderService::class.java)
            .setAction(ACTION_UPDATE)
            .putExtra(EXTRA_CONFIG, configJson)
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

    /** 当前正在播报（或最近一次播报）的内容 */
    fun getSpeakContent(): String = currentContent

    /** 记录一次“开始播报”，写入本地待上传队列（进程被杀后 JS 下次上线再补传） */
    fun appendPendingRecord(context: Context, reminderId: String, content: String, timeMillis: Long) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray(sp.getString(PREFS_PENDING_RECORDS, "[]") ?: "[]")
            val o = JSONObject()
            o.put("reminderId", reminderId)
            o.put("content", content)
            o.put("time", timeMillis)
            arr.put(o)
            val capped = JSONArray()
            val count = if (arr.length() > PENDING_MAX) PENDING_MAX else arr.length()
            for (i in 0 until count) {
                capped.put(arr.getJSONObject(i))
            }
            // 同步写盘：确保进程被立即杀掉时记录也已落盘（覆盖“进程被杀”场景）
            sp.edit().putString(PREFS_PENDING_RECORDS, capped.toString()).commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 读取本地待上传记录（不清空） */
    fun getPendingRecords(): String {
        val context = UTSAndroid.getAppContext() ?: return "[]"
        return getPendingRecords(context)
    }

    fun getPendingRecords(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(PREFS_PENDING_RECORDS, "[]") ?: "[]"
    }

    /** 清空本地待上传记录（仅在成功上传后调用） */
    fun clearPendingRecords() {
        val context = UTSAndroid.getAppContext() ?: return
        clearPendingRecords(context)
    }

    fun clearPendingRecords(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREFS_PENDING_RECORDS).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 把当前配置写入本地（进程被杀/开机后仍可恢复） */
    fun saveConfig(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(PREFS_HAS_CONFIG, true)
                .putBoolean(PREFS_MASTER_ENABLED, masterEnabled)
                .putString(PREFS_REMINDERS, remindersJson)
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
            masterEnabled = sp.getBoolean(PREFS_MASTER_ENABLED, false)
            remindersJson = sp.getString(PREFS_REMINDERS, "[]") ?: "[]"
            setConfigFromJson(remindersJson, resetSlots = false)
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

class SmartReminderService : Service() {

    private val TAG = "SmartReminder"
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var speechRepeat: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOnReceiver: ScreenOnReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    // 已排入闹钟的 requestCode（用于取消/更新）
    private val scheduledRequestCodes = mutableSetOf<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: 前台服务创建")
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=" + (intent?.action ?: "null"))
        when (intent?.action) {
            SmartReminderKeepalive.ACTION_START,
            SmartReminderKeepalive.ACTION_UPDATE -> {
                if (intent != null) {
                    val newJson = intent.getStringExtra(SmartReminderKeepalive.EXTRA_CONFIG)
                        ?: SmartReminderKeepalive.remindersJson
                    val changed = newJson != SmartReminderKeepalive.remindersJson
                    SmartReminderKeepalive.setConfigFromJson(newJson, resetSlots = true)
                    SmartReminderKeepalive.saveConfig(applicationContext)
                    // 配置真正变化时才停止当前播报；重复同步不打断
                    if (changed && SmartReminderKeepalive.speaking) {
                        stopSpeechInternal(emitStopped = true)
                    }
                }
                startForegroundCompat()
                updateNotificationText()
                scheduleAllAlarms()
            }
            SmartReminderKeepalive.ACTION_FIRE -> fire(intent)
            SmartReminderKeepalive.ACTION_RESTART -> {
                // 开机自启 / 划掉后自动复活：恢复持久化配置，重新进入前台并调度全部闹钟（不立即播报）
                SmartReminderKeepalive.loadConfig(applicationContext)
                startForegroundCompat()
                updateNotificationText()
                scheduleAllAlarms()
            }
            SmartReminderKeepalive.ACTION_STOP_SPEECH -> {
                stopSpeechInternal(emitStopped = true)
                scheduleAllAlarms()
            }
            SmartReminderKeepalive.ACTION_STOP_ALL -> {
                stopSpeechInternal(emitStopped = true)
                cancelAllAlarms()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> {
                // 进程被系统重建后恢复：读取持久化配置，重新进入前台并恢复全部闹钟
                SmartReminderKeepalive.loadConfig(applicationContext)
                startForegroundCompat()
                updateNotificationText()
                scheduleAllAlarms()
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
            if (!SmartReminderKeepalive.masterEnabled) return
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
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    SmartReminderKeepalive.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                // Android 14 以下没有 SPECIAL_USE 类型，用无类型重载避免抛异常
                startForeground(SmartReminderKeepalive.NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "startForegroundCompat: 成功")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundCompat: 失败 " + e.message)
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

    // 到点放一条高优先级全屏通知：息屏时点亮屏幕并拉起 App（闹钟/来电同款机制）
    private fun showReminderNotification(content: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    SmartReminderKeepalive.ALARM_CHANNEL_ID,
                    "提醒响铃",
                    NotificationManager.IMPORTANCE_HIGH
                )
                channel.description = "到点提醒：息屏自动亮屏并拉起 App"
                channel.setSound(null, null)
                channel.enableVibration(false)
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, SmartReminderKeepalive.ALARM_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("智能提醒")
                .setContentText(content.ifBlank { "该做正事啦" })
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(0)
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                val flags = if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getActivity(this, 999, launchIntent, flags)
                builder.setContentIntent(pi)
                // 关键：息屏时全屏拉起 App（Android 10+ 需 USE_FULL_SCREEN_INTENT）
                builder.setFullScreenIntent(pi, true)
            }
            nm.notify(SmartReminderKeepalive.ALARM_NOTIFICATION_ID, builder.build())
            Log.i(TAG, "showReminderNotification: 已发送全屏提醒")
        } catch (e: Exception) {
            Log.e(TAG, "showReminderNotification 失败 " + e.message)
        }
    }

    private fun currentSummary(): String {
        return if (!SmartReminderKeepalive.masterEnabled) {
            "今日提醒已关闭"
        } else {
            val enabledList = SmartReminderKeepalive.remindersList.filter { it.enabled }
            val times = enabledList.flatMap { it.times }
            if (enabledList.isEmpty()) "暂无提醒" else "共 ${enabledList.size} 条提醒 · 每日 ${times.size} 次"
        }
    }

    // ---------- 闹钟调度（多提醒） ----------

    private fun scheduleAllAlarms() {
        cancelAllAlarms()
        if (!SmartReminderKeepalive.masterEnabled) return
        var scheduled = 0
        for (r in SmartReminderKeepalive.remindersList) {
            if (!r.enabled) continue
            for (t in r.times) {
                val parts = t.split(":")
                if (parts.size < 2) continue
                val hour = parts[0].toIntOrNull() ?: continue
                val minute = parts[1].toIntOrNull() ?: continue
                if (hour > 23 || minute > 59) continue
                val slotKey = r.id + "@" + t
                val requestCode = slotKey.hashCode()
                val triggerAt = nextTriggerMillis(hour, minute)
                scheduleAlarm(requestCode, triggerAt, r.content, slotKey, t)
                scheduledRequestCodes.add(requestCode)
                scheduled++
            }
        }
        persistScheduledCodes()
        Log.i(TAG, "scheduleAllAlarms: 已调度 " + scheduled + " 个提醒")
    }

    private fun scheduleAlarm(
        requestCode: Int,
        triggerAt: Long,
        content: String,
        slotKey: String,
        time: String
    ) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fireIntent = Intent(this, ReminderReceiver::class.java)
            .setAction(SmartReminderKeepalive.ACTION_FIRE)
            .putExtra(SmartReminderKeepalive.EXTRA_FIRE_SLOT, slotKey)
            .putExtra(SmartReminderKeepalive.EXTRA_TIME, time)
            .putExtra(SmartReminderKeepalive.EXTRA_CONTENT, content)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, requestCode, fireIntent, flags)
        try {
            when {
                Build.VERSION.SDK_INT >= 23 -> {
                    // 统一用“闹钟”类型：精确、免 SCHEDULE_EXACT_ALARM 权限、可唤醒 Doze（息屏最可靠）
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

    private fun rescheduleSlot(slotKey: String, content: String, time: String) {
        val parts = time.split(":")
        if (parts.size < 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return
        val requestCode = slotKey.hashCode()
        scheduleAlarm(requestCode, nextTriggerMillis(hour, minute), content, slotKey, time)
        if (!scheduledRequestCodes.contains(requestCode)) {
            scheduledRequestCodes.add(requestCode)
            persistScheduledCodes()
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

    private fun cancelAlarmRequest(requestCode: Int) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val fireIntent = Intent(this, ReminderReceiver::class.java)
                .setAction(SmartReminderKeepalive.ACTION_FIRE)
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(this, requestCode, fireIntent, flags)
            alarmManager.cancel(pi)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelAllAlarms() {
        val codes = LinkedHashSet<Int>()
        codes.addAll(scheduledRequestCodes)
        // 读取上次持久化的闹钟 code，覆盖进程被重建的情况
        try {
            val sp = getSharedPreferences(SmartReminderKeepalive.PREFS_NAME, Context.MODE_PRIVATE)
            val stored = sp.getString(SmartReminderKeepalive.PREFS_SCHEDULED_CODES, "") ?: ""
            for (s in stored.split(",")) {
                s.trim().toIntOrNull()?.let { codes.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        for (code in codes) {
            cancelAlarmRequest(code)
        }
        scheduledRequestCodes.clear()
    }

    private fun persistScheduledCodes() {
        try {
            val sp = getSharedPreferences(SmartReminderKeepalive.PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putString(SmartReminderKeepalive.PREFS_SCHEDULED_CODES, scheduledRequestCodes.joinToString(","))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- 触发播报 ----------

    private fun fire(intent: Intent?) {
        if (!SmartReminderKeepalive.masterEnabled) return
        val slotKey = intent?.getStringExtra(SmartReminderKeepalive.EXTRA_FIRE_SLOT) ?: ""
        val time = intent?.getStringExtra(SmartReminderKeepalive.EXTRA_TIME) ?: ""
        val content = intent?.getStringExtra(SmartReminderKeepalive.EXTRA_CONTENT) ?: ""
        Log.i(TAG, "fire: slot=" + slotKey + " speaking=" + SmartReminderKeepalive.speaking)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val firedDate = SmartReminderKeepalive.firedSlots[slotKey]
        if (slotKey.isNotEmpty() && firedDate == today) {
            // 今天该槽位已触发过：只续排次日，不重复播报
            Log.i(TAG, "fire: 今日已触发，跳过（只续排次日）")
            if (time.isNotBlank()) rescheduleSlot(slotKey, content, time)
            return
        }
        if (slotKey.isNotEmpty()) {
            SmartReminderKeepalive.firedSlots[slotKey] = today
        }
        SmartReminderKeepalive.currentContent = content.ifBlank { "该做正事啦" }
        if (SmartReminderKeepalive.speaking) {
            // 正在播报则不再打断；只续排
            Log.i(TAG, "fire: 正在播报，不打断（只续排）")
            if (time.isNotBlank()) rescheduleSlot(slotKey, content, time)
            return
        }
        // 开始播报：写入本地待上传队列，由 JS 侧下次活络时批量补传
        val reminderId = slotKey.substringBefore('@')
        if (reminderId.isNotBlank()) {
            SmartReminderKeepalive.appendPendingRecord(applicationContext, reminderId, content, System.currentTimeMillis())
        }
        Log.i(TAG, "fire: 开始播报，reminderId=" + reminderId)
        showReminderNotification(content) // 息屏自动亮屏 + 拉起 App
        startSpeech()
        if (time.isNotBlank()) rescheduleSlot(slotKey, content, time)
    }

    private fun startSpeech() {
        SmartReminderKeepalive.emitState("speaking")
        acquireWakeLock()
        registerScreenOnReceiver()
        ensureTtsReady()
    }

    // 息屏触发播报期间，用户一解锁/亮屏就把 App 调到前台显示“确认已收到”
    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        try {
            val r = ScreenOnReceiver()
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, filter)
            }
            screenOnReceiver = r
            Log.i(TAG, "registerScreenOnReceiver: 已注册（亮屏自动回前台）")
        } catch (e: Exception) {
            Log.e(TAG, "registerScreenOnReceiver 失败 " + e.message)
        }
    }

    private fun unregisterScreenOnReceiver() {
        try {
            if (screenOnReceiver != null) {
                unregisterReceiver(screenOnReceiver)
                screenOnReceiver = null
                Log.i(TAG, "unregisterScreenOnReceiver: 已注销")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureTtsReady() {
        if (ttsReady) {
            speakLoop()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
                Log.i(TAG, "TTS init status=" + status + " SUCCESS=" + TextToSpeech.SUCCESS)
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
                    Log.e(TAG, "TTS 语音引擎初始化失败 status=" + status)
                    Toast.makeText(this, "语音引擎初始化失败，无法播报", Toast.LENGTH_SHORT).show()
                    SmartReminderKeepalive.emitState("stopped")
                }
            })
        }
    }

    private fun speakLoop() {
        val t = tts ?: return
        if (!ttsReady || !SmartReminderKeepalive.speaking) return
        val text = SmartReminderKeepalive.currentContent.ifBlank { "该做正事啦" }
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
        handler.postDelayed(runnable, 3_000L)
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
        unregisterScreenOnReceiver()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(SmartReminderKeepalive.ALARM_NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        // 保留触发所需的 extras
        serviceIntent.putExtras(intent)
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
 * 亮屏自动回到 App：息屏触发持续播报后，用户一解锁/亮屏就把 App 调到前台显示“确认已收到”
 */
class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SmartReminderKeepalive.speaking) return
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                context.startActivity(launchIntent)
                Log.i("SmartReminder", "ScreenOnReceiver: 已拉起 App 到前台")
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
            if (!SmartReminderKeepalive.masterEnabled) return
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
