# 智能提醒（smartreminder）

双端联动的语音提醒 App：一台手机设为主机（可配置多条独立提醒，每条提醒可设置每天多个时间点），另一台设为从机，到点后由从机持续语音播报提醒，直到从机用户手动确认。基于 uni-app（Vue3）+ uniCloud + uni-push 2.0 + 手机系统 TTS + Android 前台服务保活（方案B）。

## 功能

- 主机：多提醒总开关 + 多条独立提醒；每条提醒可单独开/关、编辑内容、设置每天多个时间点；一键保存到云端
- 同步机制（推送驱动，无轮询，无 ACK 等待）：
  1. 主机保存配置：云函数写入云端，并立即给所有从机推送“配置已更新”
  2. 从机收到推送后自行调 `get-config` 拉取新配置
  3. 不做 ACK 等待/重传；若推送失败，从机只在“冷启动 / 重新回到前台且临近下次提醒”时同步（高频打开不重复同步），另有“每天固定 00:00”定时拉取兜底（`ENABLE_DAILY_FALLBACK` 常量可关，每天最多 1 次）
- 从机播报：到点后全屏遮罩 + 每 3 秒重复语音播报，直到点击“确认已收到”
- 提醒记录：从机每次**真正开始播报**都按提醒 id 记一条；原生保活时先写本地队列、下次活络再批量上传（覆盖进程被杀）；主机每条提醒可展开查看最近 30 条
- 息屏自动播报（方案B，仅 Android）：原生前台服务 + 精确闹钟，App 在后台/息屏时也能到点自动持续语音播报
- 语音：App 端直接调用手机系统 TTS（Android `TextToSpeech` / iOS `AVSpeechSynthesizer`）；H5/小程序端退化为 Toast 提示
- 微信小程序（mp-weixin）：仅作主机设置提醒，从机入口已隐藏，需输入家庭邀请码校验通过后使用

## 部署步骤（一次性）

1. HBuilderX 登录 DCloud 账号，获取 appid（manifest 可视化界面点“获取 appid”）
2. 右键 `uniCloud-aliyun` -> 创建并关联阿里云服务空间（建议按量计费，见费用说明）
3. 展开 `uniCloud-aliyun/cloudfunctions`，分别右键 `set-config`、`get-config`、`register-device`、`log-reminder`、`get-records`、`verify-invite` -> 上传部署。上传 `verify-invite` 后，到 uniCloud 网页控制台 -> 云函数 -> `verify-invite` -> 环境变量，新增 `FAMILY_INVITE_CODE`（= 你的家庭邀请码，建议足够长/随机）；未配置时小程序端将无法通过校验。
4. 开通 uni-push：uniCloud 网页控制台（https://unicloud.dcloud.net.cn/）-> 推送设置 -> 开通（uni-push 2.0）；正式版运行会自动用到 `opendb-tempdata`、`opendb-device`、`uni-id-device` 三张 opendb 表，若控制台没有请手动创建。
5. 超时：云控制台把 `set-config` 超时设为 60 秒（云函数内推送带 3 秒超时兜底，不会长时间占用）；`set-config/package.json` 已启用 `uni-cloud-push` 扩展库。
6. 在 HBuilderX 中确认 manifest 的 `App模块配置 -> Push（消息推送）` 已勾选（uni-push 2.0），然后：
   - 测试：直接“运行到手机或模拟器”（标准基座）可验证同步/推送/前台 TTS；**息屏保活需要自定义基座**（见下）
   - 正式：`发行 -> 原生App-云打包` 生成安装包（离线推送/厂商通道需在控制台配置并勾选厂商）
7. **手动创建集合 `reminder_records`**：uniCloud 网页控制台 -> 云数据库 -> 新建集合，名称填 `reminder_records`（阿里云不支持云函数内自动建集合）
8. 两台手机分别安装（一台设主机、一台设从机）

## 微信小程序上架注意

- 上传 `verify-invite` 云函数后，在 uniCloud 网页控制台 -> 云函数 -> `verify-invite` -> 环境变量，新增 `FAMILY_INVITE_CODE`（你的家庭邀请码，建议足够长/随机）；未配置时小程序端校验会失败。
- 小程序端调用 uniCloud 需要把云端域名加入“服务器域名”白名单：微信公众平台 -> 开发设置 -> 服务器域名 -> request 合法域名，添加 uniCloud 域名（默认 `https://api.next.bspapp.com`，具体以 uniCloud 控制台/云函数 URL化 显示的为准）。manifest 里的 `urlCheck:false` 只对微信开发者工具生效，**不**影响线上，线上仍会强校验该域名。
- 小程序端仅作“主机”使用（从机入口已隐藏），不涉及用户隐私采集类 API；提交审核前请在公众平台填写《用户隐私保护指引》。
- 用微信开发者工具“上传”后，到公众平台“版本管理 -> 开发版本 -> 提交审核”。

## 开源 / 公共仓库注意事项

- 仓库不含任何密钥、签名证书（如 `smartreminder.keystore`）或云服务空间 ID；`.hbuilderx/`、`unpackage/` 均已 gitignore。
- `manifest.json` 中的 `__UNI__00A13F1` 是本项目的 DCloud APPID（`mp-weixin` 里的 `wx4304cb812a03dda4` 是微信小程序的 appid）；若 fork 请替换成你自己的 appid，并在 HBuilderX 里重新关联你自己的服务空间。
- 克隆后运行时**不会自动连接原作者的服务空间，也不会产生原作者的费用**：服务空间绑定存在本机 `.hbuilderx`/DCloud 账号，不在仓库里。你必须自己创建/关联服务空间、上传部署云函数、开通 uni-push，功能才会工作。
- 从机列表只显示主机“开启”的提醒（关闭的隐藏）；从机页面与提醒弹窗已做适老化字号放大。
- `project.config.json`、`project.private.config.json` 是微信开发者工具自动生成的本地配置（含测试 appid / 本地设置），已加入 `.gitignore`，请勿提交。
- 微信小程序端家庭邀请码不写在仓库/客户端里，改为云端校验：邀请码配置在 `verify-invite` 云函数的环境变量 `FAMILY_INVITE_CODE` 中（uniCloud 网页控制台 -> 云函数 -> verify-invite -> 环境变量，建议用足够长/随机的值避免被暴力猜出）；未配置时云端默认拒绝所有校验。App 端不做此校验。

## 提醒记录（从机触发日志）

- **口径**：只在从机**真正开始播报**那一次记一条（同一时间点当天只播一次；正在播报时别的提醒点只续排不打断、不记），按提醒 id 存到 `reminder_records`，每条记录含时间、内容。
- **覆盖进程被杀**：原生保活模式下，原生在前台服务 `fire()` 开始播报前先把记录写入本机 `SharedPreferences`（`pending_records`）；即使 App 进程被杀，记录也留在磁盘；下次 JS 活络（收到推送同步 / 冷启动 / 手动同步 / 每日兜底）时由 `flushPendingRecords()` 批量调 `log-reminder` 上传，成功后清空本地队列。上传失败或集合未创建时**不清空**，保留待补传。
- **主机展示**：主机每条提醒卡片下有“提醒记录（最近30条）”，点“展开”懒加载调 `get-records`，不增加同步数据量。
- **无多从机**：记录按设备各自上传，同一提醒 id 的多次触发按时间倒序展示最近 30 条。

## 方案B：Android 息屏自动持续语音播报（前台服务保活）

从机在 Android 上通过 `uni_modules/smart-reminder-keepalive`（UTS 插件）实现“息屏也能自动语音播报”：

- 原理：从机首次同步时启动一个**原生前台服务**（常驻通知“智能提醒服务运行中”），用**精确闹钟**在提醒时间唤醒 CPU；到点后由服务内的原生 `TextToSpeech` 每 3 秒持续播报，直到用户在 App 里点“确认已收到”（或切换角色时自动停止）。播报期间持有唤醒锁，避免息屏后 CPU 休眠中断。
- 与页面的配合：原生开始/停止播报会通过回调通知页面显示/隐藏全屏遮罩；页面 JS 不再重复发声（避免双重播报）；App 被杀后重新打开，也会根据原生服务状态恢复遮罩。
- 每个提醒的每个时间点每天只触发一次：原生服务和页面各自记录“已触发槽位”，确认后当天该时间点不再重复；不同时间点互不影响。

启用步骤（重要，新手照做）：

1. 插件包含原生配置（AndroidManifest.xml），**标准基座不支持**，必须：
   - 调试：HBuilderX 菜单 `运行 -> 运行到手机或模拟器 -> 制作自定义调试基座`（云打包生成，需已配置证书），再运行到手机
   - 发布：`发行 -> 原生App-云打包`，使用自有证书 `smartreminder.keystore`（见“发布信息”）
   - 本次已升级为多提醒（多小时），原生 `hybrid.kt` 有改动，需**重新制作自定义调试基座 / 重新云打包**后才生效
2. 手机首次进入从机模式时会申请“通知”权限（Android 13+ 必点“允许”，否则常驻通知不显示，但服务仍会运行）
3. 建议到手机系统设置里把应用加入：电池优化白名单（不允许电池优化）、自启动白名单（小米/华为/OPPO/vivo 等各品牌“应用启动管理”里允许后台运行），并把应用从“最近任务”里锁定，防止被一键清理杀掉
4. 真机测试方法：设好提醒时间后按电源键息屏，等到点看是否自动播报；也可在从机页面点“测试语音播报”验证 TTS 正常

### 防“清后台/强行停止”（代码已内置的防御）

- **配置持久化**：提醒配置写入本地（SharedPreferences），进程被杀/重启手机后不丢失
- **划掉自动复活**：用户从最近任务划掉应用时，`onTaskRemoved` 会安排一个 3 秒后的系统“闹钟”尝试重新拉起服务（30 秒内连续被清 2 次会自动放弃，避免被系统判定为恶意自启）
- **开机自启**：重启手机后（`BOOT_COMPLETED`）自动恢复前台服务与闹钟，前提是应用未被“强行停止”过
- **一键保活设置**：从机页面有“保活设置（电池白名单/自启动）”按钮，一键跳转电池优化白名单申请页和应用详情页

用户侧要做的（效果占大头，务必照做）：

1. 打开 App 后点“保活设置”，按系统提示允许“忽略电池优化”
2. 到系统“应用管理/应用启动管理”里允许本应用自启动和后台运行（小米/华为/OPPO/vivo 等品牌各有入口）
3. 在“最近任务”界面把本应用**下拉锁定**（防止一键清理），不要点“强行停止”，不要用清理类 App 清理它
4. 平时正常退出：直接按 Home 键或息屏即可，不需要退出应用

已知限制（Android 系统层面的现实，请如实告知用户）：

- 用户主动“强行停止”（设置里的 Force Stop）后，**任何 App 都无法自己复活**（Android 铁律，系统闹钟也一样），只有再次打开 App 才会恢复——本应用打开时会自动恢复服务
- “清后台”可以通过白名单 + 锁定 + 自动复活把失败率降到很低，但不同厂商 ROM 的杀后台策略不同，做不到数学上的 100%
- iOS 系统不允许第三方 App 息屏后持续播放语音（iOS 上从机功能不完整），所以**从机只部署 Android**；iOS 只作主机（设置提醒，不播报）
- 未授予“闹钟和提醒”精确闹钟权限（Android 12+）时，会自动退化为系统“闹钟”类型或 1 分钟内模糊闹钟，仍有较强可靠性；建议在系统设置里允许“闹钟和提醒”

## 费用说明（按量计费，年成本约 10-20 元）

- 成本大头是“云函数有调用的小时数”：阿里云规定某小时只要有调用，最少计 90 GBs（约 0.01 元/小时）
- 本设计下：主机每天保存 1-3 次 + 从机收到推送/启动/每日兜底，每月有调用小时约 90-150 小时 -> 约 0.9-1.5 元/月
- 每日兜底开关（`ENABLE_DAILY_FALLBACK`）保留的成本约 0.3 元/月，作为推送失效时的保险；如确认推送足够可靠，可改为 `false` 省掉
- 提醒记录新增 `log-reminder` + `get-records`：从机触发次数少（每天每条提醒 1 次），预计额外约 0.2~0.6 元/月，主要在展开记录时才读
- 建议在 uniCloud 控制台为各资源设置每日上限，并在监控告警里打开余额/用量提醒，防止意外超支

## 使用方法

1. 主机：选择“本机作为主机” -> 打开“全部提醒”总开关 -> 逐条设置提醒内容与多个时间点（可用“+ 添加时间”“+ 添加一条提醒”），保存并同步
2. 从机（Android）：选择“本机作为从机”，主机保存后 1-2 秒内自动同步；页面显示“后台保活：前台服务运行中”即表示息屏可播报
3. 到点后从机全屏播报（息屏时由原生服务发声），点“确认已收到”停止

## 目录结构

- `pages/index/index.vue` 主页面（角色选择 / 主机设置 / 从机同步 / 持续播报遮罩 / 保活插件接入）
- `App.vue` 推送注册与“配置已更新”监听
- `utils/tts.js` 系统 TTS 语音播报工具（speak / stopSpeaking，前台兜底）
- `uni_modules/smart-reminder-keepalive/` 方案B UTS 插件（仅 Android）
  - `utssdk/app-android/hybrid.kt` 原生前台服务：常驻通知、精确闹钟、原生 TTS 持续播报、唤醒锁
  - `utssdk/app-android/index.uts` 插件导出 API（startKeepAlive / updateReminder / stopSpeech / stopKeepAlive / getState / getSpeakContent / getPendingRecords / clearPendingRecords / onStateChanged）
  - `utssdk/app-android/AndroidManifest.xml` 前台服务与权限声明（自定义基座/云打包生效）
- `uniCloud-aliyun/cloudfunctions/set-config` 写入配置 + 推送
- `uniCloud-aliyun/cloudfunctions/get-config` 读取配置
- `uniCloud-aliyun/cloudfunctions/register-device` 注册从机推送 clientid
- `uniCloud-aliyun/cloudfunctions/log-reminder` 追加从机触发记录（按提醒 id，最近 30 条）
- `uniCloud-aliyun/cloudfunctions/get-records` 按提醒 id 读取触发记录
- 云数据库集合 `reminder_records`（需手动创建）
- `manifest.json` 应用配置（已开启 uni-push 2.0）
- `pages.json` 页面路由

## 发布信息

- 应用名：智能提醒（manifest.json 的 name）
- APPID：__UNI__00A13F1
- Android 包名：com.lin.smartreminder（manifest.json app-plus.distribute.android.packagename）
- 签名证书：自有 Android 签名证书（别名 smartreminder），务必另行安全保管，**切勿提交到代码仓库**；云打包时在 HBuilderX 上传使用
- 云打包：自有证书上传 smartreminder.keystore，包名必须与上面一致
- 插件要求：HBuilderX 4.0+（UTS 原生混编需 HBuilderX 4.25+，编译 SDK 34）
