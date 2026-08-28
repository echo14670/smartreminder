<script>
export default {
  onLaunch() {
    // #ifdef APP-PLUS
    // 注册推送客户端，让主机修改配置后能实时通知从机
    uni.getPushClientId({
      success: (res) => {
        const clientid = res.cid
        const oldCid = uni.getStorageSync('pushCid')
        uni.setStorageSync('pushCid', clientid)
        // cid 未变则不重复注册：避免调试反复运行 App 时每次都调 register-device 刷调用次数
        if (oldCid === clientid) return
        uniCloud.callFunction({
          name: 'register-device',
          data: { clientid, role: uni.getStorageSync('role') || 'slave' }
        }).catch((err) => console.warn('register-device 失败', err))
      },
      fail: (err) => console.warn('getPushClientId 失败（请确认已打包开启 Push 模块）', err)
    })
    // 收到“配置已更新”推送：从机立即重新同步
    uni.onPushMessage((res) => {
      // 兼容不同 uni-push 版本回调结构：payload 可能在 res.payload 或 res.data.payload
      let payload = res && res.payload
      if (!payload && res && res.data) {
        try {
          payload = typeof res.data === 'string' ? JSON.parse(res.data) : (res.data.payload || res.data)
        } catch (e) {
          payload = null
        }
      }
      if (res.type === 'receive' && payload && payload.type === 'config-updated') {
        uni.$emit('config-updated')
      }
    })
    // #endif
  },
  onShow() {
    console.log('App Show')
  },
  onHide() {
    console.log('App Hide')
  }
}
</script>

<style>
/* 全局样式 */
</style>
