package ai.camarade.core

private var passed = 0
private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }

fun main() {
    test("코드 글자는 서버 발급 글자(0/O, 1/I/L 제외)와 같다") {
        check(CODE_ALPHABET == "ABCDEFGHJKMNPQRSTUVWXYZ23456789") { CODE_ALPHABET }
        check(CODE_ALPHABET.length == 31)
        check(listOf('0', 'O', '1', 'I', 'L').none { it in CODE_ALPHABET })
    }
    test("입력 정리: 소문자는 대문자로, 쓰지 않는 글자와 공백은 버리고 6자리까지") {
        check(normalizeCode("5xw g7f") == "5XWG7F"); check(normalizeCode("5XW-G7F9K") == "5XWG7F")
        check(normalizeCode("0O1IL") == ""); check(normalizeCode("") == "")
    }
    test("6자리가 다 차고 허용된 글자일 때만 완성") {
        check(isCodeComplete("5XWG7F")); check(!isCodeComplete("5XWG7")); check(!isCodeComplete("5XWG70")); check(!isCodeComplete("5xwg7f"))
    }
    test("자판: 숫자 한 줄 + 영문과 지우기, 한 줄 8칸, 코드 글자를 하나도 빠뜨리지 않음") {
        val rows = codeKeypad()
        check(rows.size == 4) { "rows=${rows.size}" }
        check(rows.all { it.size == KEYPAD_COLUMNS }) { rows.map { it.size }.toString() }
        check(rows[0] == "23456789".map { it.toString() })
        check(rows.last().last() == KEY_BACKSPACE)
        val letters = rows.flatten().filter { it != KEY_BACKSPACE }.joinToString("")
        check(letters.toSet() == CODE_ALPHABET.toSet() && letters.length == CODE_ALPHABET.length) { letters }
    }
    test("자판 입력: 글자 추가, 6자리 넘으면 무시, 지우기, 빈 코드 지우기는 그대로") {
        var c = ""
        for (k in listOf("5", "X", "W", "G", "7", "F")) c = pressCodeKey(c, k)
        check(c == "5XWG7F"); check(pressCodeKey(c, "A") == "5XWG7F")
        check(pressCodeKey(c, KEY_BACKSPACE) == "5XWG7"); check(pressCodeKey("", KEY_BACKSPACE) == "")
        check(pressCodeKey("AB", "0") == "AB"); check(pressCodeKey("AB", "OO") == "AB")
    }
    test("초대 문구와 불러 주기 쉬운 표기") {
        check(inviteMessage("주말엔, 동해", "강릉 안목해변", "5XWG7F") == "카마라드 방 초대 · 주말엔, 동해 · 목적지 강릉 안목해변 · 초대 코드 5XWG7F")
        check(spokenCode("5XWG7F") == "5XW G7F")
    }
    println("$passed invite code tests passed")
}
