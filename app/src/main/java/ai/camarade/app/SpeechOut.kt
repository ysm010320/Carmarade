package ai.camarade.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as PleosTtsError
import ai.pleos.playground.tts.constant.Mode as PleosTtsMode
import ai.pleos.playground.tts.listener.EventListener as PleosTtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as PleosTtsInit

/**
 * 음성 읽어주기. Pleos TextToSpeech(ON_DEVICE)를 먼저 쓰고, 초기화에 실패하면 Android 기본 TTS로 바꾼다.
 * 여러 문장이 겹치면 차례대로 읽는다(받은 메시지와 거리 알림이 동시에 올 수 있음).
 */
class SpeechOut(context: Context, private val onReady: () -> Unit) {
    var ready=false; private set
    var engine=""; private set
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val queue=ArrayDeque<String>()
    private var busy=false
    private var pleos: PleosTts?=null
    private var fallback: AndroidTts?=null
    // onDone이 오지 않는 경우에도 다음 문장으로 넘어가도록 하는 안전장치.
    private val unstick=Runnable { busy=false;next() }
    private val events=object: PleosTtsEvents {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { main.post { finished() } }
        override fun onStop() { main.post { finished() } }
        override fun onError(errorType: PleosTtsError) { main.post { finished() } }
    }

    init {
        try {
            val t=PleosTts(app,PleosTtsMode.ON_DEVICE);pleos=t
            t.initialize(object: PleosTtsInit {
                override fun onSuccess() { main.post { t.addEventListener(events);ready=true;engine="Pleos";onReady() } }
                override fun onFailure(errorType: PleosTtsError) { main.post { pleos=null;useAndroid() } }
            })
        } catch(_: Throwable) { pleos=null;useAndroid() }
    }
    private fun useAndroid() {
        runCatching { pleos?.release() };pleos=null
        var tts: AndroidTts?=null
        tts=AndroidTts(app) { status ->
            if(status==AndroidTts.SUCCESS) {
                val r=tts?.setLanguage(Locale.KOREAN)
                if(r!=AndroidTts.LANG_MISSING_DATA && r!=AndroidTts.LANG_NOT_SUPPORTED) { ready=true;engine="Android";onReady() }
            }
        }
        fallback=tts
    }
    fun say(text: String) {
        if(!ready || text.isBlank()) return
        queue.addLast(text)
        if(!busy) next()
    }
    private fun next() {
        val text=queue.removeFirstOrNull() ?: return
        val p=pleos
        if(p!=null) {
            busy=true;main.removeCallbacks(unstick);main.postDelayed(unstick,15_000)
            try { p.speak(text) } catch(_: Throwable) { finished() }
        } else {
            fallback?.speak(text,AndroidTts.QUEUE_ADD,null,"carmarade")
            next()
        }
    }
    private fun finished() { main.removeCallbacks(unstick);busy=false;next() }
    fun stop() { queue.clear();main.removeCallbacks(unstick);busy=false;runCatching { pleos?.stop() };runCatching { fallback?.stop() } }
    fun release() {
        stop()
        runCatching { pleos?.removeEventListener(events);pleos?.release() }
        runCatching { fallback?.shutdown() }
        pleos=null;fallback=null;ready=false
    }
}
