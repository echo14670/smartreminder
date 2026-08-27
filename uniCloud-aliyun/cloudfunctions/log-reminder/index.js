'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_records'
const MAX = 30

exports.main = async (event) => {
  // 支持批量：event.reminders = [{ reminderId, content, time }]
  let list = Array.isArray(event.reminders) ? event.reminders : []
  if (!list.length && event.reminderId) {
    list = [{ reminderId: event.reminderId, content: event.content, time: event.time }]
  }
  if (!list.length) {
    return { code: 400, msg: '参数错误' }
  }

  try {
    for (const item of list) {
      const reminderId = item.reminderId
      if (!reminderId) continue
      const time = Number(item.time) || Date.now()
      const content = typeof item.content === 'string' ? item.content.slice(0, 200) : ''
      let records = []
      try {
        const res = await db.collection(COLLECTION).doc(reminderId).get()
        const data = res.data && res.data.length ? res.data[0] : null
        if (data && Array.isArray(data.records)) records = data.records
      } catch (e) {
        // 文档不存在，按新建处理
      }
      records.unshift({ time, content, ts: Date.now() })
      if (records.length > MAX) records = records.slice(0, MAX)
      await db.collection(COLLECTION).doc(reminderId).set({
        reminderId,
        records,
        updatedAt: Date.now()
      })
    }
    return { code: 0, msg: 'ok' }
  } catch (e) {
    const msg = (e && e.message) || String(e)
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 500, msg: '未找到集合，请先在 uniCloud 网页控制台创建集合 reminder_records' }
    }
    return { code: 500, msg }
  }
}
