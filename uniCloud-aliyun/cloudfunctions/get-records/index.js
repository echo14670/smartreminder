'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_records'

exports.main = async (event) => {
  const ids = Array.isArray(event.reminderIds) ? event.reminderIds : []
  const out = {}
  try {
    for (const id of ids) {
      try {
        const res = await db.collection(COLLECTION).doc(id).get()
        const data = res.data && res.data.length ? res.data[0] : null
        out[id] = data && Array.isArray(data.records) ? data.records : []
      } catch (e) {
        out[id] = []
      }
    }
    return { code: 0, data: out }
  } catch (e) {
    const msg = (e && e.message) || String(e)
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 0, data: {}, msg: '集合未创建，请先在 uniCloud 控制台创建 reminder_records' }
    }
    return { code: 500, msg }
  }
}
