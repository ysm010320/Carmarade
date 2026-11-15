package ai.camarade.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import ai.camarade.core.VOICE_FRAME_SAMPLES
import ai.camarade.core.VOICE_RATE
import ai.camarade.core.rms
import ai.camarade.core.ulawDecodeFrame
import ai.camarade.core.ulawEncodeFrame
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 통화 한 번. 마이크를 20ms씩 잘라 [send]로 내보내고, [receive]로 들어온 일행의 음성을 사람마다 따로 재생한다.
 * 음성은 이 기기에서 저장하지 않는다. 서버를 거쳐 같은 통화 중인 일행에게만 간다.
 * 마이크는 화면이 보이는 동안에만 계속 들어온다(앱이 가려지면 Android가 마이크를 막을 수 있음).
 */
class VoiceCall(private val context: Context, private val send: (ByteArray) -> Unit, private val onProblem: (String) -> Unit) {
    @Volatile private var running = false
    private var started = false
    private var recorder: AudioRecord? = null
    private var effects = listOf<android.media.audiofx.AudioEffect>()
    private var capture: Thread? = null
    private var player: Thread? = null
    private val inbox = LinkedBlockingQueue<Pair<String, ByteArray>>()
    private val tracks = HashMap<String, AudioTrack>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private val sent = AtomicInteger()
    private val received = AtomicInteger()
    @Volatile private var lastMicRms = 0.0
    @Volatile private var lastPlayRms = 0.0
    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val active: Boolean get() = started && running

    fun start(): Boolean {
        if (started) return running
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, VOICE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, VOICE_FRAME_SAMPLES * 2 * 4))
        } catch (e: Exception) { Log.w(TAG, "AudioRecord 만들기 실패: $e"); null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return false }
        // 스피커 소리가 마이크로 되돌아오는 울림·잡음을 기기가 지원하면 줄인다.
        effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } else null)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs()).setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
        recorder = rec;running = true;started = true;sent.set(0);received.set(0)
        Log.i(TAG, "통화 시작 echoCancel=${effects.any { it is AcousticEchoCanceler }} noiseSuppress=${effects.any { it is NoiseSuppressor }} debuggable=$debuggable")
        capture = Thread({ captureLoop(rec) }, "voice-capture").also { it.start() }
        player = Thread({ playLoop() }, "voice-play").also { it.start() }
        return true
    }

    /** 일행이 보낸 음성 한 조각(µ-law). 재생이 밀려 지연이 쌓이면 오래된 것부터 버린다. */
    fun receive(from: String, payload: ByteArray) {
        if (!running) return
        received.incrementAndGet()
        if (inbox.size > MAX_QUEUED) { while (inbox.size > MAX_QUEUED / 2) inbox.poll() }
        inbox.offer(from to payload)
    }

    fun stop() {
        if (!started) return
        started = false;running = false
        Log.i(TAG, "통화 끝 보낸=${sent.get()} 받은=${received.get()}")
        runCatching { capture?.join(300) };runCatching { player?.interrupt();player?.join(300) }
        effects.forEach { runCatching { it.release() } };effects = emptyList()
        runCatching { recorder?.stop() };runCatching { recorder?.release() };recorder = null
        tracks.values.forEach { runCatching { it.stop() };runCatching { it.release() } };tracks.clear();inbox.clear()
        focus?.let { audio.abandonAudioFocusRequest(it) };focus = null
    }

    private fun attrs() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun captureLoop(rec: AudioRecord) {
        val pcm = ShortArray(VOICE_FRAME_SAMPLES)
        var silent = 0;var tonePhase = 0.0;var usedTone = false;var lastLog = 0L
        try { rec.startRecording() } catch (e: Exception) { onProblem("마이크를 열지 못했어요.");running = false;return }
        while (running) {
            var n = 0
            while (n < pcm.size && running) { val r = rec.read(pcm, n, pcm.size - n); if (r <= 0) { onProblem("마이크가 멈췄어요.");running = false;return }; n += r }
            if (!running) break
            lastMicRms = rms(pcm)
            // 디버그 빌드 전용: 시험용 에뮬레이터의 마이크는 소리가 없어서, 0.5초 이상 무음이면 440Hz 신호음을 대신 보내 통신 경로를 확인한다.
            silent = if (lastMicRms < 1.0) silent + 1 else 0
            if (debuggable && silent > 25) {
                usedTone = true
                for (i in pcm.indices) { pcm[i] = (4000 * Math.sin(tonePhase)).toInt().toShort();tonePhase += 2 * Math.PI * 440 / VOICE_RATE }
            }
            send(ulawEncodeFrame(pcm, n));sent.incrementAndGet()
            val now = System.currentTimeMillis()
            if (now - lastLog > 2000) { lastLog = now;Log.i(TAG, "보낸=${sent.get()} 받은=${received.get()} 마이크RMS=${"%.1f".format(lastMicRms)} 받은소리RMS=${"%.1f".format(lastPlayRms)}${if (usedTone) " (무음→시험 신호음)" else ""}") }
        }
    }

    private fun playLoop() {
        while (running) {
            val (from, payload) = try { inbox.take() } catch (e: InterruptedException) { return }
            val track = tracks.getOrPut(from) {
                val min = AudioTrack.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioTrack.Builder().setAudioAttributes(attrs())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(VOICE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, VOICE_FRAME_SAMPLES * 2 * 6)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
            }
            val pcm = ulawDecodeFrame(payload)
            lastPlayRms = rms(pcm)
            // 차량의 통화 음량 기본값이 작아서(Pleos 에뮬레이터: 45단계 중 20, 약 -25dB) 받은 소리를 키운다. 넘치는 값은 잘라 낸다.
            for (i in pcm.indices) pcm[i] = (pcm[i] * PLAY_GAIN).toInt().coerceIn(-32768, 32767).toShort()
            track.write(pcm, 0, pcm.size)
        }
    }

    companion object {
        private const val TAG = "CarmaradeVoice"
        /** 재생 대기 한도(20ms 단위 25개=약 0.5초). 넘으면 지연이 쌓이지 않게 오래된 음성을 버린다. */
        private const val MAX_QUEUED = 25
        /** 받은 소리를 키우는 배율(약 +9.5dB). */
        private const val PLAY_GAIN = 3f
    }
}
