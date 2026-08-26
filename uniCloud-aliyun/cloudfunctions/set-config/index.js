'use strict'
const db = uniCloud.database()
const COLLECTION = 'reminder_config'
const DOC_ID = 'main'
// 注意：阿里云 uniCloud 不支持 createCollection，集合需在 uniCloud 网页控制台手动创建：
//   reminder_config（主配置）、devices（从机注册）

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

exports.main = async (event) => {
  const enabled = event.enabled
  const time = event.time
  const content = event.content
  if (typeof enabled !== 'boolean' || typeof time !== 'string' || !/^\d{2}:\d{2}$/.test(time)) {
    return { code: 400, msg: '参数错误' }
  }

  // 每次保存生成新版本号，从机用它在 confirm-sync 中确认（ACK）
  const version = String(Date.now()) + '-' + Math.floor(Math.random() * 100000)

  try {
    await db.collection(COLLECTION).doc(DOC_ID).set({
      enabled,
      time,
      content: typeof content === 'string' ? content : '',
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

  const ackedOf = async (clientid) => {
    try {
      const res = await db.collection('devices').doc(clientid).get()
      const doc = res.data && res.data.length ? res.data[0] : null
      return !!doc && doc.ackVersion === version
    } catch (e) {
      return false
    }
  }

  // 第一轮：推送所有从机
  const waiting = []
  for (const d of clients) {
    const clientid = d._id
    if (clientid && (await push(clientid))) {
      waiting.push(clientid)
    }
  }

  // 可靠传输：等待确认，未确认者重传一次（约 12 秒内完成，超时需在控制台调大）
  const acked = []
  await sleep(5000)
  for (const clientid of waiting) {
    if (await ackedOf(clientid)) {
      acked.push(clientid)
    } else {
      await push(clientid)
    }
  }
  await sleep(5000)
  for (const clientid of waiting) {
    if (await ackedOf(clientid)) {
      acked.push(clientid)
    } else {
      console.warn('从机未确认，将由启动/每日兜底同步补齐', clientid)
    }
  }

  return {
    code: 0,
    msg: 'ok',
    version,
    slaveCount: clients.length,
    ackedCount: [...new Set(acked)].length,
    acked: [...new Set(acked)]
  }
}
