// 语音播报工具：App 端调用手机系统 TTS，无需第三方插件
// Android: android.speech.tts.TextToSpeech
// iOS:     AVSpeechSynthesizer
// 非 App 平台（H5/小程序）退化为 Toast 提示

let androidTts = null
let iosSynth = null

export function speak(text) {
  if (!text) return
  // #ifdef APP-PLUS
  // #ifdef APP-ANDROID
  androidSpeak(text)
  // #endif
  // #ifdef APP-IOS
  iosSpeak(text)
  // #endif
  // #endif
  // #ifndef APP-PLUS
  uni.showToast({ title: '语音播报：' + text, icon: 'none' })
  // #endif
}

// 停止当前播报（用户确认关闭时调用）
export function stopSpeaking() {
  // #ifdef APP-ANDROID
  try {
    if (androidTts) androidTts.stop()
  } catch (e) {}
  // #endif
  // #ifdef APP-IOS
  try {
    if (iosSynth) iosSynth.stopSpeakingAtBoundary(1)
  } catch (e) {}
  // #endif
}

// #ifdef APP-ANDROID
function androidSpeak(text) {
  try {
    const main = plus.android.runtimeMainActivity()
    const TextToSpeech = plus.android.importClass('android.speech.tts.TextToSpeech')
    const Locale = plus.android.importClass('java.util.Locale')
    if (!androidTts) {
      const listener = plus.android.implements('android.speech.tts.TextToSpeech$OnInitListener', {
        onInit: function (status) {
          if (status === TextToSpeech.SUCCESS) {
            androidTts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, 'smart-reminder')
          }
        }
      })
      androidTts = new TextToSpeech(main, listener)
    } else {
      androidTts.setLanguage(Locale.SIMPLIFIED_CHINESE)
      androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, 'smart-reminder')
    }
  } catch (e) {
    console.error('Android TTS 失败', e)
    uni.showToast({ title: '语音播报失败', icon: 'none' })
  }
}
// #endif

// #ifdef APP-IOS
function iosSpeak(text) {
  try {
    const AVSpeechSynthesizer = plus.ios.importClass('AVSpeechSynthesizer')
    const AVSpeechUtterance = plus.ios.importClass('AVSpeechUtterance')
    const AVSpeechSynthesisVoice = plus.ios.importClass('AVSpeechSynthesisVoice')
    if (!iosSynth) iosSynth = new AVSpeechSynthesizer()
    const utterance = AVSpeechUtterance.speechUtteranceWithString(text)
    utterance.setRate(0.45)
    utterance.setPitchMultiplier(1.0)
    const voice = AVSpeechSynthesisVoice.voiceWithLanguage('zh-CN')
    if (voice) utterance.setVoice(voice)
    iosSynth.speakUtterance(utterance)
  } catch (e) {
    console.error('iOS TTS 失败', e)
    uni.showToast({ title: '语音播报失败', icon: 'none' })
  }
}
// #endif