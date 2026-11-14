package ai.camarade.core

/** 초대 코드 6자리. 헷갈리는 0/O, 1/I/L은 서버 발급 코드에도 없다(server.js CODE_ALPHABET과 같아야 함). */
const val CODE_LENGTH = 6
const val CODE_DIGITS = "23456789"
const val CODE_LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ"
const val CODE_ALPHABET = CODE_LETTERS + CODE_DIGITS
/** 화면 자판의 지우기 키. */
const val KEY_BACKSPACE = "⌫"
const val KEYPAD_COLUMNS = 8

/** 입력한 글자를 코드로 정리: 소문자는 대문자로, 코드에 쓰지 않는 글자는 버리고, 6자리까지만 남긴다. */
fun normalizeCode(raw: String): String = raw.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)

fun isCodeComplete(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }

/**
 * 화면 자판 배열. Pleos 전체 화면 키보드 대신 앱이 직접 그린다.
 * 첫 줄은 숫자, 그다음은 영문, 마지막 칸은 지우기. 한 줄에 [KEYPAD_COLUMNS]개.
 */
fun codeKeypad(): List<List<String>> =
    (listOf(CODE_DIGITS.map { it.toString() }) + (CODE_LETTERS.map { it.toString() } + KEY_BACKSPACE).chunked(KEYPAD_COLUMNS))

/** 자판 키를 눌렀을 때 새 코드. 6자리가 차면 더 입력하지 않는다. */
fun pressCodeKey(code: String, key: String): String = when {
    key == KEY_BACKSPACE -> code.dropLast(1)
    code.length >= CODE_LENGTH || key.length != 1 || key[0] !in CODE_ALPHABET -> code
    else -> code + key
}

/** 방장이 메시지로 보낼 초대 문구. */
fun inviteMessage(roomTitle: String, destination: String, code: String): String =
    "카마라드 방 초대 · $roomTitle · 목적지 $destination · 초대 코드 $code"

/** 말로 불러 주기 쉽게 3글자씩 끊어 보여 줌: "5XWG7F" → "5XW G7F". */
fun spokenCode(code: String): String = code.chunked(3).joinToString(" ")
