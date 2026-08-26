<template>
  <view class="page">
    <!-- 未选择角色 -->
    <view v-if="!mode" class="card">
      <text class="page-title">智能提醒</text>
      <text class="desc">两台手机分别安装本 App：一台设为主机（设置提醒），一台设为从机（定点语音播报）。主机保存后，通过云推送通知从机，从机确认后主机显示同步结果。</text>
      <button class="btn" type="primary" @click="chooseMode('host')">本机作为主机（设置提醒）</button>
      <button class="btn" type="default" @click="chooseMode('slave')">本机作为从机（接收提醒）</button>
    </view>

    <!-- 主机面板 -->
    <view v-else-if="mode === 'host'" class="card">
      <view class="header">
        <text class="page-title">主机设置</text>
        <text class="tag">本机：主机</text>
      </view>
      <view class="row">
        <text class="label">今日是否提醒</text>
        <switch :checked="enabled" color="#007aff" @change="onEnabledChange" />
      </view>
      <view class="row">
        <text class="label">提醒时间</text>
        <picker mode="time" :value="time" @change="onTimeChange">
          <view class="time-value">{{ time }}</view>
        </picker>
      </view>
      <view class="row col">
        <text class="label">提醒内容（从机语音播报）</text>
        <input class="input" v-model="content" placeholder="例如：该喝水啦" />
      </view>
      <view class="row">
        <text class="label">同步状态</text>
        <text class="muted">{{ syncStatus }}</text>
      </view>
      <button class="btn" type="primary" @click="pushConfig">保存并同步</button>
      <button class="btn link" type="warn" plain @click="resetMode">切换角色</button>
    </view>

    <!-- 从机面板 -->
    <view v-else class="card">
      <view class="header">
        <text class="page-title">从机</text>
        <text class="tag">本机：从机</text>
      </view>
      <view class="row"><text class="label">今日提醒</text><text :class="reminderEnabled ? 'state-on' : 'state-off'">{{ reminderEnabled ? '开' : '关' }}</text></view>
      <view class="row"><text class="label">提醒时间</text><text class="value">{{ reminderTime || '--' }}</text></view>
      <view class="row"><text class="label">提醒内容</text><text class="value">{{ reminderContent || '--' }}</text></view>
      <view class="row"><text class="label">同步方式</text><text class="muted">云推送(确认重传) + 每日兜底</text></view>
      <view class="row"><text class="label">上次同步</text><text class="muted">{{ lastSyncText }}</text></view>
      <view class="row"><text class="label">上次播报</text><text class="muted">{{ lastSpeakText }}</text></view>
      <!-- #ifdef APP-ANDROID -->
      <view class="row"><text class="label">后台保活</text><text :class="keepAliveActive ? 'state-on' : 'state-off'">{{ keepAliveActive ? '前台服务运行中（息屏可播报）' : '未启用' }}</text></view>
      <!-- #endif -->
      <!-- #ifdef APP-ANDROID -->
      <button class="btn" type="warn" plain @click="openKeepAliveSettings">保活设置（电池白名单/自启动）</button>
      <!-- #endif -->
      <button class="btn" type="primary" @click="testSpeak">测试语音播报</button>
      <button class="btn" type="default" @click="syncNow">立即同步</button>
      <button class="btn link" type="warn" plain @click="resetMode">切换角色</button>
      <view class="tip">提示：收到主机推送后自动拉取新配置并上报确认；主机未收到确认会重传一次。另有每天提醒前 2 分钟的兜底拉取（可在代码里关闭）。到点后全屏持续语音播报，直到点击“确认已收到”。Android 从机开启“后台保活”后，息屏/锁屏也能自动持续语音播报（需云打包/自定义基座，并允许应用在后台运行）。</view>
    </view>

    <!-- 持续播报遮罩 -->
    <view v-if="reminderActive" class="mask">
      <view class="mask-card">
        <text class="mask-title">⏰ 提醒时间到</text>
        <text class="mask-content">{{ reminderContent || '该做正事啦' }}</text>
        <text class="mask-sub">持续语音播报中，请确认收到</text>
        <button class="mask-btn" type="primary" @click="confirmReminder">确认已收到</button>
      </view>
    </view>
  </view>
</template>

<script>
import { speak, stopSpeaking } from '@/utils/tts.js'
// #ifdef APP-ANDROID
// 方案B：Android 息屏持续语音播报（前台服务保活插件）
import {
  startKeepAlive,
  updateReminder,
  stopSpeech,
  stopKeepAlive,
  getState,
  onStateChanged
} from '@/uni_modules/smart-reminder-keepalive'
// #endif

const REPEAT_INTERVAL = 10000
// 每日兜底拉取开关：推送失效时的保险（每天 1 次云函数调用，约 0.3 元/月）
const ENABLE_DAILY_FALLBACK = true

export default {
  data() {
    return {
      mode: '',
      enabled: true,
      time: '08:00',
      content: '该做正事啦',
      syncStatus: '未同步',
      reminderEnabled: false,
      reminderTime: '',
      reminderContent: '',
      lastSyncText: '--',
      lastSpeakText: '--',
      dailyTimer: null,
      repeatTimer: null,
      lastTriggerDate: '',
      reminderActive: false,
      keepAliveActive: false,
      keepAliveSupported: false
    }
  },
  onLoad() {
    this.mode = uni.getStorageSync('role') || ''
    const config = uni.getStorageSync('hostConfig') || {}
    if (config.enabled !== undefined) this.enabled = config.enabled
    if (config.time) this.time = config.time
    if (config.content) this.content = config.content
    uni.$on('config-updated', this.onConfigUpdated)
    // #ifdef APP-ANDROID
    this.setupKeepAlive()
    // #endif
  },
  onShow() {
    if (this.mode === 'slave') {
      this.syncNow()
      // #ifdef APP-ANDROID
      this.ensureNotificationPermission()
      // #endif
    }
  },
  onUnload() {
    this.stopRepeat()
    this.clearDailyTimer()
    uni.$off('config-updated', this.onConfigUpdated)
  },
  methods: {
    chooseMode(mode) {
      this.mode = mode
      uni.setStorageSync('role', mode)
      if (mode === 'slave') {
        this.syncNow()
        // #ifdef APP-ANDROID
        this.ensureNotificationPermission()
        // #endif
      }
    },
    resetMode() {
      this.stopRepeat()
      this.clearDailyTimer()
      stopSpeaking()
      // #ifdef APP-ANDROID
      this.safeStopKeepAlive()
      // #endif
      this.reminderActive = false
      uni.removeStorageSync('role')
      this.mode = ''
    },
    onConfigUpdated() {
      // 主机修改配置后，从机立即重新同步
      if (this.mode === 'slave') {
        this.syncNow()
      }
    },
    onEnabledChange(e) {
      this.enabled = e.detail.value
    },
    onTimeChange(e) {
      this.time = e.detail.value
    },
    pushConfig() {
      const data = {
        enabled: this.enabled,
        time: this.time,
        content: this.content
      }
      uni.setStorageSync('hostConfig', data)
      this.syncStatus = '同步中...'
      uniCloud.callFunction({
        name: 'set-config',
        data
      }).then((res) => {
        const r = (res && res.result) || {}
        if (r.slaveCount > 0 && r.ackedCount === r.slaveCount) {
          this.syncStatus = '已同步，从机已确认 ' + this.nowText()
        } else if (r.slaveCount > 0) {
          this.syncStatus = '已同步，从机未确认(' + (r.ackedCount || 0) + '/' + r.slaveCount + ')'
        } else {
          this.syncStatus = '已同步（暂无在线从机）'
        }
      }).catch((err) => {
        this.syncStatus = '同步失败'
        this.showCloudError(err)
      })
    },
    syncNow() {
      uniCloud.callFunction({
        name: 'get-config'
      }).then((res) => {
        const data = res.result && res.result.data
        if (data) {
          this.reminderEnabled = !!data.enabled
          this.reminderTime = data.time || ''
          this.reminderContent = data.content || ''
          // 上报确认（ACK），让主机知道已收到最新配置
          this.ackSync(data.version)
        } else {
          this.reminderEnabled = false
          this.reminderTime = ''
          this.reminderContent = ''
        }
        this.lastSyncText = this.nowText()
        this.scheduleDailySync()
        // #ifdef APP-ANDROID
        this.syncKeepAlive()
        // #endif
        this.checkReminder()
      }).catch((err) => {
        this.lastSyncText = '同步失败'
        this.showCloudError(err)
      })
    },
    ackSync(version) {
      if (!version) return
      const clientid = uni.getStorageSync('pushCid')
      if (!clientid) return
      uniCloud.callFunction({
        name: 'confirm-sync',
        data: { clientid, version }
      }).catch((err) => console.warn('确认同步失败', err))
    },
    // 每天在提醒时间前 2 分钟拉一次配置，作为推送失效时的兜底
    scheduleDailySync() {
      if (!ENABLE_DAILY_FALLBACK || !this.reminderTime) return
      this.clearDailyTimer()
      const parts = this.reminderTime.split(':')
      const target = new Date()
      target.setHours(parseInt(parts[0], 10), parseInt(parts[1], 10), 0, 0)
      let delay = target.getTime() - 2 * 60 * 1000 - Date.now()
      if (delay < 0) delay += 24 * 60 * 60 * 1000
      this.dailyTimer = setTimeout(() => {
        this.syncNow()
      }, delay)
    },
    clearDailyTimer() {
      if (this.dailyTimer) {
        clearTimeout(this.dailyTimer)
        this.dailyTimer = null
      }
    },
    checkReminder() {
      if (!this.reminderEnabled || !this.reminderTime) return
      const now = new Date()
      const today = this.ymd(now)
      // 每天只进入一次提醒状态（持续播报直到用户确认）
      if (today === this.lastTriggerDate) return
      // 到达提醒时间后的 5 分钟窗口内触发，兼容定时误差
      const parts = this.reminderTime.split(':')
      const target = new Date()
      target.setHours(parseInt(parts[0], 10), parseInt(parts[1], 10), 0, 0)
      const diff = now.getTime() - target.getTime()
      if (diff >= 0 && diff <= 5 * 60 * 1000) {
        this.lastTriggerDate = today
        this.lastSpeakText = this.nowText()
        this.startReminder()
      }
    },
    startReminder() {
      this.reminderActive = true
      uni.vibrateLong()
      this.lastSpeakText = this.nowText()
      if (this.keepAliveActive) {
        // 方案B：由原生前台服务持续播报（息屏/后台也可靠），JS 只负责界面遮罩
        return
      }
      this.speakContent()
      this.stopRepeat()
      this.repeatTimer = setInterval(() => {
        this.speakContent()
      }, REPEAT_INTERVAL)
    },
    speakContent() {
      speak(this.reminderContent || '现在是' + this.reminderTime + '，该做正事啦')
    },
    confirmReminder() {
      this.stopRepeat()
      stopSpeaking()
      // #ifdef APP-ANDROID
      this.safeStopSpeech()
      // #endif
      this.reminderActive = false
      this.lastSpeakText = '已确认 ' + this.nowText()
    },
    stopRepeat() {
      if (this.repeatTimer) {
        clearInterval(this.repeatTimer)
        this.repeatTimer = null
      }
    },
    testSpeak() {
      uni.vibrateLong()
      speak(this.reminderContent || '语音播报测试')
      this.lastSpeakText = '手动测试 ' + this.nowText()
    },
    // ---------- 方案B：Android 息屏持续播报（前台服务保活） ----------
    setupKeepAlive() {
      // #ifdef APP-ANDROID
      try {
        this.keepAliveSupported = true
        onStateChanged((state) => {
          if (state === 'speaking') {
            this.reminderActive = true
            this.lastSpeakText = '原生播报中 ' + this.nowText()
          } else if (state === 'stopped') {
            this.stopRepeat()
            this.reminderActive = false
          }
        })
        // 从机重启 App 后恢复遮罩（原生服务可能仍在播报）
        if (getState() === 'speaking') {
          this.reminderActive = true
        }
      } catch (e) {
        console.warn('保活插件初始化失败（标准基座不支持原生配置，需自定义基座/云打包）', e)
        this.keepAliveSupported = false
      }
      // #endif
    },
    syncKeepAlive() {
      // #ifdef APP-ANDROID
      if (!this.keepAliveSupported) return
      const config = {
        enabled: this.reminderEnabled,
        time: this.reminderTime || '08:00',
        content: this.reminderContent || ''
      }
      try {
        let ok = updateReminder(config)
        if (!ok) {
          ok = startKeepAlive(config)
        }
        this.keepAliveActive = !!ok
      } catch (e) {
        console.warn('保活插件调用失败', e)
        this.keepAliveActive = false
      }
      // #endif
    },
    safeStopSpeech() {
      // #ifdef APP-ANDROID
      try {
        stopSpeech()
      } catch (e) {
        console.warn('停止原生播报失败', e)
      }
      // #endif
    },
    safeStopKeepAlive() {
      // #ifdef APP-ANDROID
      try {
        stopKeepAlive()
      } catch (e) {
        console.warn('停止保活服务失败', e)
      }
      this.keepAliveActive = false
      // #endif
    },
    ensureNotificationPermission() {
      // #ifdef APP-ANDROID
      try {
        plus.android.requestPermissions(['android.permission.POST_NOTIFICATIONS'], () => {
        }, (err) => {
          console.warn('通知权限申请失败（不影响前台服务，但常驻通知可能不显示）', err)
        })
      } catch (e) {
        console.warn('申请通知权限失败', e)
      }
      // #endif
    },
    // 引导用户设置：电池优化白名单 + 应用详情页（各品牌“自启动/后台运行”都在设置里）
    openKeepAliveSettings() {
      // #ifdef APP-ANDROID
      try {
        const main = plus.android.runtimeMainActivity()
        const Intent = plus.android.importClass('android.content.Intent')
        const Settings = plus.android.importClass('android.provider.Settings')
        const Uri = plus.android.importClass('android.net.Uri')
        const pkg = main.getPackageName()
        // 1) 申请“忽略电池优化”白名单（系统会弹窗让用户允许）
        try {
          const intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
          intent.setData(Uri.parse('package:' + pkg))
          main.startActivity(intent)
        } catch (e1) {
          console.warn('电池优化白名单跳转失败', e1)
        }
        // 2) 打开应用详情页（自启动/后台运行管理入口通常在这里）
        setTimeout(() => {
          try {
            const intent2 = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent2.setData(Uri.parse('package:' + pkg))
            main.startActivity(intent2)
          } catch (e2) {
            console.warn('应用详情页跳转失败', e2)
          }
        }, 400)
      } catch (e) {
        console.warn('保活设置引导失败', e)
      }
      // #endif
    },
    // ---------- 方案B 结束 ----------
    showCloudError(err) {
      console.error(err)
      uni.showModal({
        title: '云同步不可用',
        content: '请确认：1. HBuilderX 已登录 DCloud 账号；2. 已关联云服务空间；3. set-config、get-config、register-device、confirm-sync 四个云函数已上传部署；4. 已开通 uni-push 并重新打包。',
        showCancel: false
      })
    },
    hhmm(date) {
      const h = date.getHours()
      const m = date.getMinutes()
      return (h < 10 ? '0' + h : '' + h) + ':' + (m < 10 ? '0' + m : '' + m)
    },
    ymd(date) {
      return date.getFullYear() + '-' + (date.getMonth() + 1) + '-' + date.getDate()
    },
    nowText() {
      const d = new Date()
      return this.hhmm(d) + ' ' + this.ymd(d)
    }
  }
}
</script>

<style>
.page {
  padding: 40rpx;
  background-color: #f5f6fa;
  min-height: 100vh;
  box-sizing: border-box;
}
.card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 40rpx;
  box-shadow: 0 4rpx 20rpx rgba(0, 0, 0, 0.04);
}
.page-title {
  font-size: 40rpx;
  font-weight: bold;
  color: #222222;
  display: block;
}
.desc {
  margin-top: 20rpx;
  color: #666666;
  font-size: 26rpx;
  line-height: 1.6;
  display: block;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30rpx;
}
.tag {
  font-size: 24rpx;
  color: #007aff;
  background: rgba(0, 122, 255, 0.08);
  padding: 6rpx 20rpx;
  border-radius: 999rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.row.col {
  flex-direction: column;
  align-items: flex-start;
}
.label {
  font-size: 28rpx;
  color: #333333;
}
.value {
  font-size: 28rpx;
  color: #222222;
}
.muted {
  font-size: 26rpx;
  color: #999999;
}
.state-on {
  color: #07c160;
  font-weight: bold;
}
.state-off {
  color: #999999;
}
.time-value {
  font-size: 34rpx;
  color: #007aff;
  font-weight: bold;
}
.input {
  margin-top: 16rpx;
  width: 100%;
  height: 76rpx;
  border: 1rpx solid #e5e5e5;
  border-radius: 12rpx;
  padding: 0 20rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.btn {
  margin-top: 30rpx;
}
.btn.link {
  border: none;
}
.tip {
  margin-top: 30rpx;
  font-size: 24rpx;
  color: #999999;
  line-height: 1.6;
}
.mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}
.mask-card {
  width: 600rpx;
  background: #ffffff;
  border-radius: 24rpx;
  padding: 60rpx 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.mask-title {
  font-size: 40rpx;
  font-weight: bold;
  color: #e64340;
}
.mask-content {
  margin-top: 30rpx;
  font-size: 34rpx;
  color: #222222;
  text-align: center;
}
.mask-sub {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #999999;
}
.mask-btn {
  margin-top: 50rpx;
  width: 100%;
}
</style>