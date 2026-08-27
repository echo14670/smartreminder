'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_config'
const DOC_ID = 'main'

// 归一化配置：兼容旧格式 {enabled,time,content} 与新增的多提醒格式
const normalize = (d) => {
  if (!d) return null
  if (Array.isArray(d.reminders)) {
    return {
      masterEnabled: d.masterEnabled !== false,
      reminders: d.reminders,
      version: d.version,
      updatedAt: d.updatedAt
    }
  }
  return {
    masterEnabled: d.enabled !== false,
    reminders: [{ id: 'r1', enabled: true, times: [d.time || '08:00'], content: d.content || '' }],
    version: d.version,
    updatedAt: d.updatedAt
  }
}

exports.main = async () => {
  try {
    const res = await db.collection(COLLECTION).doc(DOC_ID).get()
    const doc = res.data && res.data.length ? res.data[0] : null
    return { code: 0, data: normalize(doc) }
  } catch (e) {
    const msg = (e && e.message) || String(e)
    // 集合未创建时按“空配置”处理，避免从机端一直弹错误
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 0, data: null, msg: '集合未创建，请先在 uniCloud 控制台创建 reminder_config' }
    }
    return { code: 500, msg }
  }
}
