'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_config'
const DOC_ID = 'main'

exports.main = async () => {
  try {
    const res = await db.collection(COLLECTION).doc(DOC_ID).get()
    const doc = res.data && res.data.length ? res.data[0] : null
    return { code: 0, data: doc }
  } catch (e) {
    const msg = (e && e.message) || String(e)
    // 集合未创建时按“空配置”处理，避免从机端一直弹错误
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 0, data: null, msg: '集合未创建，请先在 uniCloud 控制台创建 reminder_config' }
    }
    return { code: 500, msg }
  }
}
