<template>
  <view class="page">
    <!-- 未选择角色 -->
    <view v-if="!mode" class="card card-center">
      <button class="btn" type="primary" @click="chooseMode('host')">本机作为主机（设置提醒）</button>
      <button class="btn" type="default" @click="chooseMode('slave')">本机作为从机（接收提醒）</button>
    </view>

    <!-- 主机面板 -->
    <view v-else-if="mode === 'host'" class="card">
      <view class="header">
        <text class="page-title">主机</text>
        <text class="tag">本机：主机</text>
      </view>
      <view class="row">
        <text class="label">全部提醒</text>
        <switch :checked="masterEnabled" color="#007aff" @change="onMasterEnabledChange" />
      </view>
      <view class="row">
        <text class="label">同步状态</text>
        <text :class="syncStatus === '待同步' ? 'state-warn' : (syncStatus === '已同步' ? 'state-on' : 'muted')">{{ syncStatus }}</text>
      </view>

      <view v-for="(r, idx) in reminders" :key="r.id" class="reminder-item">
        <view class="row">
          <text class="label">提醒 {{ idx + 1 }}</text>
          <switch :checked="r.enabled" color="#007aff" @change="onReminderEnabledChange(idx, $event)" />
        </view>
        <view class="row col">
          <text class="label">提醒内容</text>
          <input class="input" v-model="r.content" placeholder="例如：该喝水啦" @input="markDirty" />
        </view>
        <view class="times-block">
          <text class="label">每日提醒时间</text>
          <view class="times-list">
            <view v-for="(t, ti) in r.times" :key="t + '_' + ti" class="time-chip">
              <picker mode="time" :value="t" @change="onTimeChange(idx, ti, $event)">
                <view class="time-value">{{ t }}</view>
              </picker>
              <text class="time-del" @click="removeTime(idx, ti)">×</text>
            </view>
            <button class="time-add" type="default" size="mini" @click="addTime(idx)">+ 添加时间</button>
          </view>
        </view>
        <view class="row" @click="toggleRecords(r.id)">
          <text class="label">提醒记录（最近30条）</text>
          <text class="muted">{{ recordExpanded[r.id] ? '收起 ▲' : '展开 ▼' }}</text>
        </view>
        <view v-if="recordExpanded[r.id]" class="records-block">
          <text v-if="recordsLoading[r.id]" class="muted">加载中…</text>
          <text v-else-if="reminderRecords[r.id] && reminderRecords[r.id].length" class="record-tip">最近 {{ reminderRecords[r.id].length }} 条</text>
          <view v-for="(rec, ri) in reminderRecords[r.id] || []" :key="ri" class="record-item">
            <text class="record-time">{{ rec.timeText }}</text>
            <text class="record-content">{{ rec.content || '（无内容）' }}</text>
          </view>
          <text v-if="!recordsLoading[r.id] && !(reminderRecords[r.id] && reminderRecords[r.id].length)" class="muted">暂无记录</text>
        </view>
        <button class="btn link" type="warn" plain @click="removeReminder(idx)">删除这条提醒</button>
      </view>

      <button class="btn" type="default" @click="addReminder">+ 添加一条提醒</button>
      <button class="btn" type="primary" @click="pushConfig">保存并同步</button>
      <button class="btn link" type="warn" plain @click="resetMode">切换角色</button>
    </view>

    <!-- 从机面板 -->
    <view v-else class="card slave-card">
      <view class="header">
        <text class="page-title">从机</text>
        <text class="tag">本机：从机</text>
      </view>
      <view class="row"><text class="label">今日提醒</text><text :class="slaveEnabled ? 'state-on' : 'state-off'">{{ slaveEnabled ? '开' : '关' }}</text></view>
      <view class="row"><text class="label">提醒数量</text><text class="value">{{ visibleSlaveReminders.length }} 条 · 每日 {{ totalSlaveTimes }} 次</text></view>
      <view v-for="(r, idx) in visibleSlaveReminders" :key="r.id" class="reminder-item">
        <view class="row">
          <text class="label">{{ idx + 1 }}.{{ r.content || '（未填内容）' }}</text>
          <text :class="r.enabled ? 'state-on' : 'state-off'">{{ r.enabled ? '开' : '关' }}</text>
        </view>
        <view class="row"><text class="label">时间</text><text class="value">{{ r.times.join('、') || '--' }}</text></view>
      </view>
      <text v-if="!visibleSlaveReminders.length" class="empty-tip">主机当前未开启任何提醒</text>
      <!-- #ifdef APP-PLUS -->
      <view class="row" v-if="isAndroid"><text class="label">后台保活</text><text :class="keepAliveActive ? 'state-on' : 'state-off'">{{ keepAliveActive ? '前台服务运行中（息屏可播报）' : '未启用' }}</text></view>
      <!-- #endif -->
      <!-- #ifdef APP-PLUS -->
      <button class="btn" type="warn" plain v-if="isAndroid" @click="openKeepAliveSettings">保活设置（电池白名单/自启动）</button>
      <!-- #endif -->
      <button class="btn" type="primary" @click="testSpeak">测试语音播报</button>
      <button class="btn" type="default" @click="syncNow">立即同步</button>
      <button class="btn link" type="warn" plain @click="resetMode">切换角色</button>
    </view>

    <!-- 持续播报遮罩 -->
    <view v-if="reminderActive" class="mask">
      <view class="mask-card">
        <text class="mask-title">⏰ 提醒时间到</text>
        <text class="mask-content">{{ remindContent || '该做正事啦' }}</text>
        <text class="mask-sub">持续语音播报中，请确认收到</text>
        <button class="mask-btn" type="primary" @click="confirmReminder">确认已收到</button>
      </view>
    </view>
  </view>
</template>

<script>
import { speak, stopSpeaking } from '@/utils/tts.js'
// #ifdef APP-PLUS
// 方案B：Android 息屏持续语音播报（前台服务保活插件，多提醒）
import {
  startKeepAlive,
  updateReminder,
  stopSpeech,
  stopKeepAlive,
  getState,
  getSpeakContent,
  onStateChanged,
  getPendingRecords,
  clearPendingRecords
} from '@/uni_modules/smart-reminder-keepalive'
// #endif

const REPEAT_INTERVAL = 3000
// 每日兜底拉取开关：推送失效时的保险（每天约 1 次云函数调用）
const ENABLE_DAILY_FALLBACK = true
// 仅在“重新回到前台”且即将到提醒时才同步，避免频繁打开 App 产生大量云函数调用
const SYNC_NEAR_MINUTES = 15
const SYNC_NEAR_COOLDOWN_MS = 5 * 60 * 1000

function makeReminder() {
  return {
    id: 'r' + Date.now() + Math.floor(Math.random() * 1000),
    enabled: true,
    times: ['08:00'],
    content: '该做正事啦'
  }
}

export default {
  data() {
    return {
      mode: '',
      // 主机配置
      masterEnabled: true,
      reminders: [makeReminder()],
      syncStatus: '未同步',
      // 从机展示
      slaveEnabled: false,
      slaveReminders: [],
      lastSyncText: '--',
      lastSpeakText: '--',
      // 调度
      dailyTimer: null,
      nextTimer: null,
      repeatTimer: null,
      lastTriggerKey: '',
      lastFallbackDate: '',
      reminderActive: false,
      remindContent: '该做正事啦',
      // 保活
      keepAliveActive: false,
      keepAliveSupported: false,
      // 通知权限只申请一次，避免每次 onShow 都弹框导致 App Hide/Show 死循环
      _notifRequested: false,
      // 同步节流
      _coldStart: true,
      _lastSyncAt: 0,
      // 提醒记录（主机展示）
      reminderRecords: {},
      recordExpanded: {},
      recordsLoading: {}
    }
  },
  computed: {
    totalSlaveTimes() {
      let n = 0
      for (const r of this.slaveReminders || []) {
        if (r.enabled) n += (r.times || []).length
      }
      return n
    },
    visibleSlaveReminders() {
      return (this.slaveReminders || []).filter((r) => r.enabled !== false)
    },
    isAndroid() {
      // #ifdef APP-PLUS
      try {
        return uni.getSystemInfoSync().platform === 'android'
      } catch (e) {
        return false
      }
      // #endif
      // #ifndef APP-PLUS
      return false
      // #endif
    }
  },
  onLoad() {
    let plat = '?'
    try { plat = uni.getSystemInfoSync().platform || '?' } catch (e) {}
    console.log('[SMARTREMINDER] onLoad 执行，mode=' + uni.getStorageSync('role') + '，实际平台=' + plat)
    this.mode = uni.getStorageSync('role') || ''
    const config = uni.getStorageSync('hostConfig') || {}
    this.normalizeHostConfig(config)
    this.refreshSyncState()
    this._coldStart = true
    this._lastSyncAt = 0
    uni.$on('config-updated', this.onConfigUpdated)
    // #ifdef H5
    // 浏览器多标签页测试：主机保存后，同源 storage 事件触发从机立即同步
    this._h5Storage = (e) => {
      if (e && e.key && e.key.indexOf('__slave_sync_notify__') !== -1 && this.mode === 'slave') {
        this.syncNow()
      }
    }
    window.addEventListener('storage', this._h5Storage)
    // #endif
    // #ifdef APP-PLUS
    this.setupKeepAlive()
    // #endif
  },
  onShow() {
    if (this.mode === 'slave') {
      // 冷启动（App 被杀后重新打开）必须同步，恢复原生服务与最新配置
      if (this._coldStart) {
        this._coldStart = false
        this.syncNow()
      } else if (this.shouldSyncOnShow()) {
        this.syncNow()
      }
      // #ifdef APP-PLUS
      this.ensureNotificationPermission()
      // 全屏提醒把 App 拉回前台后，若原生仍在播报，恢复“确认已收到”遮罩
      if (this.isAndroid && this.keepAliveSupported) {
        try {
          if (getState() === 'speaking' && !this.reminderActive) {
            this.reminderActive = true
          }
        } catch (e) {}
      }
      // #endif
    }
  },
  onUnload() {
    this.stopRepeat()
    this.clearDailyTimer()
    this.clearNextTimer()
    uni.$off('config-updated', this.onConfigUpdated)
    // #ifdef H5
    if (this._h5Storage) window.removeEventListener('storage', this._h5Storage)
    // #endif
  },
  methods: {
    // ---------- 角色 ----------
    chooseMode(mode) {
      this.mode = mode
      uni.setStorageSync('role', mode)
      if (mode === 'slave') {
        this.syncNow()
        // #ifdef APP-PLUS
        this.ensureNotificationPermission()
        // #endif
      }
    },
    resetMode() {
      this.stopRepeat()
      this.clearDailyTimer()
      this.clearNextTimer()
      stopSpeaking()
      // #ifdef APP-PLUS
      this.safeStopKeepAlive()
      // #endif
      this.reminderActive = false
      this.slaveReminders = []
      this.slaveEnabled = false
      uni.removeStorageSync('role')
      this.mode = ''
    },

    // ---------- 配置归一化 ----------
    normalizeHostConfig(cfg) {
      if (!cfg) return
      if (Array.isArray(cfg.reminders)) {
        this.masterEnabled = cfg.masterEnabled !== false
        this.reminders = cfg.reminders.map((r) => ({
          id: r.id || 'r' + Date.now(),
          enabled: r.enabled !== false,
          times: Array.isArray(r.times) && r.times.length ? r.times.slice() : ['08:00'],
          content: r.content || ''
        }))
        if (!this.reminders.length) this.reminders = [makeReminder()]
      } else if (cfg.time || cfg.content) {
        // 旧格式迁移：单条提醒
        this.masterEnabled = cfg.enabled !== false
        this.reminders = [{
          id: 'r1',
          enabled: true,
          times: [cfg.time || '08:00'],
          content: cfg.content || ''
        }]
      }
    },
    normalizeServerConfig(data) {
      if (!data) return { enabled: false, reminders: [] }
      if (Array.isArray(data.reminders)) {
        const reminders = data.reminders
          .map((r) => ({
            id: r.id || 'r' + Date.now(),
            enabled: r.enabled !== false,
            times: Array.isArray(r.times) ? r.times.slice() : [],
            content: r.content || ''
          }))
          .filter((r) => r.times.length > 0)
        return { enabled: data.masterEnabled !== false, reminders }
      }
      // 旧格式
      if (data.time || data.content) {
        return {
          enabled: data.enabled !== false,
          reminders: [{ id: 'r1', enabled: true, times: [data.time || '08:00'], content: data.content || '' }]
        }
      }
      return { enabled: false, reminders: [] }
    },

    // ---------- 提醒编辑 ----------
    onMasterEnabledChange(e) {
      this.masterEnabled = e.detail.value
      this.markDirty()
    },
    onReminderEnabledChange(idx, e) {
      const r = this.reminders[idx]
      if (r) r.enabled = e.detail.value
      this.markDirty()
    },
    onTimeChange(idx, ti, e) {
      const r = this.reminders[idx]
      if (r && r.times && r.times[ti] !== undefined) r.times[ti] = e.detail.value
      this.markDirty()
    },
    addTime(idx) {
      const r = this.reminders[idx]
      if (r) r.times = (r.times || []).concat('12:00')
      this.markDirty()
    },
    removeTime(idx, ti) {
      const r = this.reminders[idx]
      if (r && r.times && r.times.length > 1) r.times.splice(ti, 1)
      this.markDirty()
    },
    addReminder() {
      this.reminders.push(makeReminder())
      this.markDirty()
    },
    removeReminder(idx) {
      this.reminders.splice(idx, 1)
      if (!this.reminders.length) this.reminders = [makeReminder()]
      this.markDirty()
    },
    // ---------- 提醒记录（主机展示） ----------
    toggleRecords(id) {
      this.recordExpanded[id] = !this.recordExpanded[id]
      if (this.recordExpanded[id] && !this.reminderRecords[id]) {
        this.loadRecords(id)
      }
    },
    loadRecords(id) {
      this.recordsLoading[id] = true
      uniCloud.callFunction({
        name: 'get-records',
        data: { reminderIds: [id] }
      }).then((res) => {
        const data = (res.result && res.result.data) || {}
        this.reminderRecords[id] = (data[id] || []).map((r) => ({
          time: r.time,
          timeText: this.formatRecordTime(r.time),
          content: r.content || ''
        }))
      }).catch((err) => {
        console.warn('读取提醒记录失败', err)
        this.reminderRecords[id] = []
      }).finally(() => {
        this.recordsLoading[id] = false
      })
    },
    formatRecordTime(ts) {
      if (!ts) return '--'
      const d = new Date(Number(ts))
      if (isNaN(d.getTime())) return '--'
      return this.hhmm(d) + ' · ' + this.ymd(d)
    },
    // 本地配置快照，用于判断是否与“最后一次成功同步”一致
    configFingerprint() {
      return JSON.stringify({
        masterEnabled: this.masterEnabled,
        reminders: (this.reminders || []).map((r) => ({
          id: r.id,
          enabled: r.enabled,
          times: (r.times || []).slice(),
          content: r.content || ''
        }))
      })
    },
    refreshSyncState() {
      const synced = uni.getStorageSync('syncedConfigFp') || ''
      const fp = this.configFingerprint()
      this.syncStatus = (synced && synced === fp) ? '已同步' : ((this.reminders && this.reminders.length) ? '待同步' : '未同步')
    },
    markDirty() {
      this.syncStatus = '待同步'
    },

    // ---------- 主机推送 ----------
    pushConfig() {
      const data = {
        masterEnabled: this.masterEnabled,
        reminders: this.reminders
          .map((r) => ({
            id: r.id,
            enabled: r.enabled,
            times: (r.times || []).slice(),
            content: r.content
          }))
          .filter((r) => r.times.length > 0)
      }
      uni.setStorageSync('hostConfig', data)
      this.syncStatus = '同步中...'
      uniCloud.callFunction({
        name: 'set-config',
        data
      }).then((res) => {
        // 同步成功：记录本次配置快照，后续本地修改会再次变为“待同步”
        uni.setStorageSync('syncedConfigFp', this.configFingerprint())
        this.syncStatus = '已同步'
        console.log('set-config 返回:', (res && res.result) || {})
        // #ifdef H5
        // 浏览器多标签页测试：通知同源其他标签页（从机测试页）立即同步
        uni.setStorageSync('__slave_sync_notify__', String(Date.now()))
        // #endif
      }).catch((err) => {
        this.syncStatus = '同步失败'
        this.showCloudError(err)
      })
    },

    // ---------- 从机同步 ----------
    // 收到“配置已更新”推送：从机立即拉取最新配置并补传本地提醒记录
    onConfigUpdated() {
      if (this.mode === 'slave') {
        this.syncNow()
      }
    },
    syncNow() {
      uniCloud.callFunction({
        name: 'get-config'
      }).then((res) => {
        const data = res.result && res.result.data
        const cfg = this.normalizeServerConfig(data)
        this.slaveEnabled = cfg.enabled
        this.slaveReminders = cfg.reminders
        this.lastSyncText = this.nowText()
        this._lastSyncAt = Date.now()
        this.scheduleDailySync()
        // #ifdef APP-PLUS
        this.syncKeepAlive()
        this.flushPendingRecords()
        // #endif
        this.rescheduleNext()
      }).catch((err) => {
        this.lastSyncText = '同步失败'
        this.showCloudError(err)
      })
    },
    // 从机本地触发时直接上报一条记录（无原生保活/前台情况）
    logTrigger(reminderId, content) {
      uniCloud.callFunction({
        name: 'log-reminder',
        data: { reminders: [{ reminderId, content: content || '', time: Date.now() }] }
      }).catch((err) => console.warn('记录提醒触发失败', err))
    },
    // 上传原生保活记录的本地待上传队列（覆盖“进程被杀”期间的触发）
    flushPendingRecords() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
      if (!this.keepAliveSupported) return
      let json = ''
      try {
        json = getPendingRecords()
      } catch (e) {
        return
      }
      let arr = []
      try {
        arr = JSON.parse(json || '[]')
        if (!Array.isArray(arr)) arr = []
      } catch (e) {
        arr = []
      }
      if (!arr.length) return
      const reminders = arr.map((r) => ({
        reminderId: r.reminderId,
        content: r.content || '',
        time: Number(r.time) || Date.now()
      }))
      uniCloud.callFunction({
        name: 'log-reminder',
        data: { reminders }
      }).then((res) => {
        const r = res && res.result
        if (r && r.code === 0) {
          try {
            clearPendingRecords()
          } catch (e) {}
        } else {
          console.warn('补传提醒记录未成功，保留本地队列', r)
        }
      }).catch((err) => console.warn('补传提醒记录失败', err))
      // #endif
    },
    // 固定每天 00:00 拉一次配置，作为推送失效时的兜底（每天最多 1 次）。
    // 相比“最早提醒前 2 分钟”：若主机前一天新增了更早的提醒且推送失败，
    // 0 点同步能保证当天一早就用上最新配置，不会漏掉这条更早的提醒。
    scheduleDailySync() {
      if (!ENABLE_DAILY_FALLBACK) return
      this.clearDailyTimer()
      if (this.mode !== 'slave') return
      const now = Date.now()
      const midnight = new Date()
      midnight.setHours(24, 0, 0, 0) // 下一个 00:00
      let delay = midnight.getTime() - now
      if (delay < 0) delay += 24 * 60 * 60 * 1000
      this.dailyTimer = setTimeout(() => {
        const today = this.ymd(new Date())
        if (this.lastFallbackDate !== today) {
          this.lastFallbackDate = today
          this.syncNow()
        } else {
          this.scheduleDailySync()
        }
      }, delay)
    },
    clearDailyTimer() {
      if (this.dailyTimer) {
        clearTimeout(this.dailyTimer)
        this.dailyTimer = null
      }
    },

    // ---------- 从机 JS 调度 ----------
    // 下一个即将到来的提醒（含今天未到的时间点，否则是明天最早）
    nextSlotDate() {
      const slots = this.collectSlots()
      if (!slots.length) return null
      let best = null
      for (const s of slots) {
        if (!best || s.date < best.date) best = s
      }
      return best
    },
    // 重新回到前台时，是否值得同步一次
    shouldSyncOnShow() {
      const now = Date.now()
      // 距上次同步太近，不重复调用
      if (now - this._lastSyncAt < SYNC_NEAR_COOLDOWN_MS) return false
      // 下一个提醒即将到来，最后一次确保拿到最新配置
      const next = this.nextSlotDate()
      if (next && next.date.getTime() - now <= SYNC_NEAR_MINUTES * 60 * 1000) return true
      return false
    },
    collectSlots() {
      const out = []
      const now = new Date()
      for (const r of this.slaveReminders || []) {
        if (!r.enabled) continue
        for (const t of (r.times || [])) {
          const parts = String(t).split(':')
          if (parts.length < 2) continue
          const h = parseInt(parts[0], 10)
          const m = parseInt(parts[1], 10)
          if (isNaN(h) || isNaN(m)) continue
          const d = new Date()
          d.setHours(h, m, 0, 0)
          if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1)
          out.push({ key: r.id + '@' + t, time: t, content: r.content, date: d })
        }
      }
      return out
    },
    rescheduleNext() {
      this.clearNextTimer()
      if (this.mode !== 'slave' || !this.slaveEnabled) return
      const slots = this.collectSlots()
      if (!slots.length) return
      const now = Date.now()
      let best = null
      for (const s of slots) {
        const d = s.date.getTime()
        if (d > now && (!best || d < best.date.getTime())) best = s
      }
      if (!best) return
      this.nextTimer = setTimeout(() => this.onSlotFire(best), best.date.getTime() - now)
    },
    clearNextTimer() {
      if (this.nextTimer) {
        clearTimeout(this.nextTimer)
        this.nextTimer = null
      }
    },
    onSlotFire(slot) {
      const today = this.ymd(new Date())
      const key = slot.key + '#' + today
      if (this.lastTriggerKey === key) {
        this.rescheduleNext()
        return
      }
      this.lastTriggerKey = key
      this.remindContent = slot.content || '该做正事啦'
      this.startReminder(slot.key.split('@')[0])
      this.rescheduleNext()
    },
    startReminder(reminderId) {
      this.reminderActive = true
      this.lastSpeakText = this.nowText()
      if (this.keepAliveActive) {
        // 方案B：由原生前台服务持续播报（息屏/后台也可靠），JS 只负责界面遮罩；
        // 原生会在开始播报时写入本地队列，这里负责上传补传
        this.flushPendingRecords()
        return
      }
      this.speakContent()
      this.stopRepeat()
      this.repeatTimer = setInterval(() => {
        this.speakContent()
      }, REPEAT_INTERVAL)
      if (reminderId) {
        this.logTrigger(reminderId, this.remindContent)
      }
    },
    speakContent() {
      speak(this.remindContent || '该做正事啦')
    },
    confirmReminder() {
      this.stopRepeat()
      stopSpeaking()
      // #ifdef APP-PLUS
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
      // 弹出“确认已收到”遮罩，循环播报直到用户手动确认，便于验证持续播报
      this.reminderActive = true
      this.lastSpeakText = '手动测试 ' + this.nowText()
      const text = this.remindContent || '语音播报测试'
      this.remindContent = text
      this.speakContent()
      this.stopRepeat()
      this.repeatTimer = setInterval(() => {
        this.speakContent()
      }, REPEAT_INTERVAL)
    },

    // ---------- 方案B：Android 息屏持续播报（前台服务保活） ----------
    setupKeepAlive() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) {
        this.keepAliveSupported = false
        return
      }
      try {
        this.keepAliveSupported = true
        console.log('[保活] 插件可用，准备初始化')
        onStateChanged((state) => {
          if (state === 'speaking') {
            this.reminderActive = true
            try {
              const c = getSpeakContent()
              if (c) this.remindContent = c
            } catch (e) {}
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
        console.log('[保活] 初始化完成 keepAliveSupported=' + this.keepAliveSupported)
      } catch (e) {
        console.warn('[保活] 插件初始化失败（极可能是标准基座，未包含 UTS 原生插件）', e)
        this.keepAliveSupported = false
      }
      // #endif
    },
    syncKeepAlive() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
      if (!this.keepAliveSupported) {
        console.warn('[保活] keepAliveSupported=false，跳过原生服务启动（标准基座）')
        this.keepAliveActive = false
        return
      }
      const cfg = {
        enabled: this.slaveEnabled,
        reminders: (this.slaveReminders || []).map((r) => ({
          id: r.id,
          enabled: r.enabled,
          times: r.times || [],
          content: r.content || ''
        }))
      }
      const json = JSON.stringify(cfg)
      console.log('[保活] 尝试启动/更新，enabled=' + this.slaveEnabled + ' 提醒数=' + (this.slaveReminders || []).length)
      try {
        let ok = updateReminder(json)
        console.log('[保活] updateReminder=' + ok)
        if (!ok) {
          ok = startKeepAlive(json)
          console.log('[保活] startKeepAlive(fallback)=' + ok)
        }
        this.keepAliveActive = !!ok
      } catch (e) {
        console.warn('[保活] 插件调用失败', e)
        this.keepAliveActive = false
      }
      console.log('[保活] 最终 keepAliveActive=' + this.keepAliveActive)
      // #endif
    },
    safeStopSpeech() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
      try {
        stopSpeech()
      } catch (e) {
        console.warn('停止原生播报失败', e)
      }
      // #endif
    },
    safeStopKeepAlive() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
      try {
        stopKeepAlive()
      } catch (e) {
        console.warn('停止保活服务失败', e)
      }
      this.keepAliveActive = false
      // #endif
    },
    ensureNotificationPermission() {
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
      if (this._notifRequested) return
      this._notifRequested = true
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
      // #ifdef APP-PLUS
      if (!this.isAndroid) return
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
        content: '请确认：1. HBuilderX 已登录 DCloud 账号；2. 已关联云服务空间；3. set-config、get-config、register-device 三个云函数已上传部署；4. 已开通 uni-push 并重新打包。',
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
  display: flex;
  flex-direction: column;
}
.card-center {
  margin-top: auto;
  margin-bottom: auto;
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
.state-warn {
  color: #ff9900;
  font-weight: bold;
}
.slave-card .page-title {
  font-size: 46rpx;
}
.slave-card .tag {
  font-size: 28rpx;
}
.slave-card .label {
  font-size: 36rpx;
}
.slave-card .value {
  font-size: 36rpx;
}
.slave-card .state-on,
.slave-card .state-off {
  font-size: 36rpx;
}
.slave-card .btn {
  font-size: 36rpx;
  height: 92rpx;
  line-height: 92rpx;
}
.empty-tip {
  display: block;
  margin-top: 30rpx;
  font-size: 32rpx;
  color: #999999;
  text-align: center;
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
.reminder-item {
  margin-top: 24rpx;
  padding: 24rpx;
  background: #f9f9fb;
  border-radius: 16rpx;
}
.records-block {
  margin-top: 16rpx;
  padding: 16rpx;
  background: #ffffff;
  border: 1rpx solid #ececec;
  border-radius: 12rpx;
  max-height: 320rpx;
  overflow-y: auto;
}
.record-tip {
  font-size: 22rpx;
  color: #999999;
  display: block;
  margin-bottom: 8rpx;
}
.record-item {
  display: flex;
  flex-direction: column;
  padding: 10rpx 0;
  border-bottom: 1rpx solid #f2f2f2;
}
.record-item:last-child {
  border-bottom: none;
}
.record-time {
  font-size: 22rpx;
  color: #999999;
}
.record-content {
  font-size: 26rpx;
  color: #333333;
  margin-top: 4rpx;
}
.times-block {
  margin-top: 24rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid #f0f0f0;
}
.times-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16rpx;
  margin-top: 16rpx;
}
.time-chip {
  display: flex;
  align-items: center;
  background: #ffffff;
  border: 1rpx solid #e5e5e5;
  border-radius: 12rpx;
  padding: 8rpx 16rpx;
}
.time-del {
  margin-left: 12rpx;
  color: #e64340;
  font-size: 34rpx;
  line-height: 1;
}
.time-add {
  margin: 0;
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
  font-size: 52rpx;
  font-weight: bold;
  color: #e64340;
}
.mask-content {
  margin-top: 30rpx;
  font-size: 48rpx;
  color: #222222;
  text-align: center;
}
.mask-sub {
  margin-top: 16rpx;
  font-size: 34rpx;
  color: #999999;
}
.mask-btn {
  margin-top: 50rpx;
  width: 100%;
  font-size: 40rpx;
  height: 100rpx;
  line-height: 100rpx;
}
</style>
