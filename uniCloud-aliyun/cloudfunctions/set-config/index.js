'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_config'
const DOC_ID = 'main'
// 注意：阿里云 uniCloud 不支持 createCollection，集合需在 uniCloud 网页控制台手动创建：
//   reminder_config（主配置）、devices（从机注册）

const isValidTime = (t) => {
  if (typeof t !== 'string' || !/^\d{2}:\d{2}$/.test(t)) return false
  const h = Number(t.slice(0, 2))
  const m = Number(t.slice(3, 5))
  return h >= 0 && h <= 23 && m >= 0 && m <= 59
}

exports.main = async (event) => {
  const masterEnabled = event.masterEnabled === undefined ? true : !!event.masterEnabled
  const reminders = event.reminders
  if (!Array.isArray(reminders)) {
    return { code: 400, msg: '参数错误：reminders 缺失' }
  }

  // 清洗并归一化为标准结构，剔除非法时间
  const cleaned = []
  for (const r of reminders) {
    if (!r || typeof r !== 'object') continue
    const times = Array.isArray(r.times) ? r.times.filter(isValidTime) : []
    if (!times.length) continue
    cleaned.push({
      id: typeof r.id === 'string' && r.id ? r.id : 'r' + Math.random().toString(36).slice(2, 8),
      enabled: r.enabled !== false,
      times: Array.from(new Set(times)),
      content: typeof r.content === 'string' ? r.content.slice(0, 200) : ''
    })
  }

  // 每次保存生成新版本号，从机可据此判断是否需要更新
  const version = String(Date.now()) + '-' + Math.floor(Math.random() * 100000)

  try {
    await db.collection(COLLECTION).doc(DOC_ID).set({
      masterEnabled,
      reminders: cleaned,
      version,
      updatedAt: Date.now()
    })
  } catch (e) {
    const msg = (e && e.message) || String(e)
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 500, msg: '未找到集合，请先在 uniCloud 网页控制台创建集合 reminder_config 和 devices' }
    }
    return { code: 500, msg }
  }

  // 获取已注册的从机（doc._id 即推送 clientid）
  let clients = []
  try {
    const res = await db.collection('devices').where({ role: 'slave' }).limit(100).get()
    clients = res.data || []
  } catch (e) {
    console.error('读取从机列表失败', e)
  }

  const push = async (clientid) => {
    try {
      await uniPush.sendMessage({
        push_clientid: clientid,
        title: '智能提醒',
        content: '主机更新了提醒设置',
        payload: { type: 'config-updated', version }
      })
      return true
    } catch (e) {
      console.error('推送失败', clientid, e)
      return false
    }
  }

  // 方案1：写入 + 推送后立即返回，不做 ACK 等待/重传
  // 从机在收到推送后自行拉取 get-config；若推送失败，则由启动/回前台同步与每日兜底最终补齐
  for (const d of clients) {
    const clientid = d._id
    if (clientid) {
      await push(clientid)
    }
  }

  return {
    code: 0,
    msg: 'ok',
    version,
    slaveCount: clients.length
  }
}
