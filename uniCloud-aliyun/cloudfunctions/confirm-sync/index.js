'use strict'
const db = uniCloud.database()

exports.main = async (event) => {
  const clientid = event.clientid
  const version = event.version
  if (!clientid || !version) {
    return { code: 400, msg: '参数错误' }
  }
  try {
    await db.collection('devices').doc(clientid).update({
      ackVersion: version,
      ackAt: Date.now()
    })
  } catch (e) {
    // 设备文档不存在时先创建再更新；集合不存在则返回明确提示
    try {
      await db.collection('devices').doc(clientid).set({
        role: 'slave',
        ackVersion: version,
        ackAt: Date.now(),
        updatedAt: Date.now()
      })
    } catch (e2) {
      const msg = (e2 && e2.message) || String(e2)
      if (/not exist/i.test(msg) || /暂不支持/i.test(msg)) {
        return { code: 500, msg: '未找到集合，请先在 uniCloud 网页控制台创建集合 devices' }
      }
      return { code: 500, msg }
    }
  }
  return { code: 0, msg: 'ok' }
}
