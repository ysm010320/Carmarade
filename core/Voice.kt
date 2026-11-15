package ai.camarade.core

/**
 * 통화 음성 형식. 마이크 16kHz 모노 16비트를 20ms(320개)씩 잘라 µ-law(8비트)로 줄여 보낸다(초당 16KB).
 * 서버는 음성을 해석하지 않고 같은 통화 중인 다른 멤버에게 그대로 넘기기만 한다(server.js onAudio와 같은 형식).
 */
const val VOICE_RATE = 16_000
const val VOICE_FRAME_SAMPLES = 320
const val AUDIO_KIND: Byte = 0x01

private const val BIAS = 0x84
private const val CLIP = 32635

fun ulawEncode(sample: Short): Byte {
    var s = sample.toInt()
    val sign = if (s < 0) { s = -s; 0x80 } else 0
    if (s > CLIP) s = CLIP
    s += BIAS
    var exp = 7
    var mask = 0x4000
    while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
    val mantissa = (s shr (exp + 3)) and 0x0F
    return (sign or (exp shl 4) or mantissa).inv().toByte()
}

fun ulawDecode(b: Byte): Short {
    val u = b.toInt().inv() and 0xFF
    val exp = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    val s = (((mantissa shl 3) + BIAS) shl exp) - BIAS
    return (if ((u and 0x80) != 0) -s else s).toShort()
}

fun ulawEncodeFrame(pcm: ShortArray, count: Int = pcm.size) = ByteArray(count) { ulawEncode(pcm[it]) }
fun ulawDecodeFrame(bytes: ByteArray) = ShortArray(bytes.size) { ulawDecode(bytes[it]) }

/** 소리 크기(RMS, 0~32767). 마이크가 정말 소리를 받는지 확인하는 데만 쓴다. */
fun rms(pcm: ShortArray, count: Int = pcm.size): Double {
    if (count <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i]
    return Math.sqrt(sum / count)
}

/** 앱 → 서버: [0x01][방 ID 길이][방 ID][음성]. */
fun audioPacket(roomId: String, payload: ByteArray): ByteArray {
    val id = roomId.toByteArray(Charsets.UTF_8)
    require(id.size in 1..255) { "방 ID 길이" }
    return ByteArray(2 + id.size + payload.size).also {
        it[0] = AUDIO_KIND; it[1] = id.size.toByte()
        id.copyInto(it, 2); payload.copyInto(it, 2 + id.size)
    }
}

class AudioPacket(val sender: String, val payload: ByteArray)

/** 서버 → 앱: [0x01][보낸 사람 ID 길이][보낸 사람 ID][음성]. 형식이 맞지 않으면 null. */
fun parseAudioPacket(bytes: ByteArray): AudioPacket? {
    if (bytes.size < 3 || bytes[0] != AUDIO_KIND) return null
    val len = bytes[1].toInt() and 0xFF
    if (len == 0 || bytes.size < 2 + len + 1) return null
    return AudioPacket(String(bytes, 2, len, Charsets.UTF_8), bytes.copyOfRange(2 + len, bytes.size))
}
