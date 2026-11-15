package ai.camarade.core

private var passed = 0
private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }

fun main() {
    test("µ-law: 0은 0 근처로, 부호가 보존되고 큰 소리도 오차가 작다") {
        check(Math.abs(ulawDecode(ulawEncode(0)).toInt()) <= 8)
        for (v in listOf(100, 1000, 5000, 12000, 30000)) {
            val up = ulawDecode(ulawEncode(v.toShort())).toInt(); val down = ulawDecode(ulawEncode((-v).toShort())).toInt()
            check(up > 0 && down < 0) { "$v → $up/$down" }
            check(Math.abs(up - v) <= v / 16 + 8) { "$v → $up" }   // 6% 안쪽
            check(up == -down) { "$v → $up/$down" }
        }
    }
    test("µ-law: 한 프레임을 줄였다 펴도 모양이 남고 크기가 절반(바이트 수=샘플 수)") {
        val pcm = ShortArray(VOICE_FRAME_SAMPLES) { (8000 * Math.sin(2 * Math.PI * 440 * it / VOICE_RATE)).toInt().toShort() }
        val enc = ulawEncodeFrame(pcm); check(enc.size == VOICE_FRAME_SAMPLES)
        val dec = ulawDecodeFrame(enc)
        val err = rms(ShortArray(pcm.size) { (pcm[it] - dec[it]).toShort() })
        check(err < rms(pcm) * 0.05) { "오차 $err / 원본 ${rms(pcm)}" }
    }
    test("RMS: 무음은 0, 사인파는 진폭의 약 0.707배") {
        check(rms(ShortArray(100)) == 0.0)
        val pcm = ShortArray(1600) { (10000 * Math.sin(2 * Math.PI * 440 * it / VOICE_RATE)).toInt().toShort() }
        check(Math.abs(rms(pcm) - 7071.0) < 80) { rms(pcm).toString() }
    }
    test("패킷: 앱이 만든 형식을 같은 규칙으로 다시 읽고, 모자란·모르는 형식은 null") {
        val p = audioPacket("r_abc123", byteArrayOf(9, 8, 7))
        check(p[0] == AUDIO_KIND && p[1].toInt() == 8)
        val back = parseAudioPacket(p)!!
        check(back.sender == "r_abc123" && back.payload.toList() == listOf<Byte>(9, 8, 7))
        check(parseAudioPacket(byteArrayOf()) == null); check(parseAudioPacket(byteArrayOf(1, 0, 5)) == null)
        check(parseAudioPacket(byteArrayOf(2, 1, 65, 1)) == null); check(parseAudioPacket(byteArrayOf(1, 9, 65, 66)) == null)
        check(parseAudioPacket(byteArrayOf(1, 1, 65)) == null) // 음성 없음
    }
    println("OK $passed")
}
