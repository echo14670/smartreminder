'use strict'
const db = uniCloud.database()

exports.main = async (event) => {
  const clientid = event.clientid
  const role = event.role === 'host' ? 'host' : 'slave'
  if (!clientid) {
    return { code: 400, msg: '缺少 clientid' }
  }
  try {
    await db.collection('devices').doc(clientid).set({
      role,
      updatedAt: Date.now()
    })
    return { code: 0, msg: 'ok' }
  } catch (e) {
    const msg = (e && e.message) || String(e)
    if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
      return { code: 500, msg: '未找到集合，请先在 uniCloud 网页控制台创建集合 devices' }
    }
    return { code: 500, msg }
  }
}
