'use strict'
// 微信小程序端家庭邀请码校验。
// 邀请码通过云函数环境变量 FAMILY_INVITE_CODE 配置（uniCloud 网页控制台 -> 云函数 -> verify-invite -> 环境变量），
// 不写在客户端/仓库里，避免明文泄露；未配置环境变量时默认拒绝，保证安全。
exports.main = async (event) => {
  const code = event && event.code
  const expected = process.env.FAMILY_INVITE_CODE || ''
  if (!expected) {
    return { code: 0, valid: false, msg: '服务端未配置家庭邀请码' }
  }
  const valid = typeof code === 'string' && code.trim() === expected.trim()
  return { code: 0, valid }
}
