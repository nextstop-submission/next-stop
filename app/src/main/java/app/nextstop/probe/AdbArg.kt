package app.nextstop.probe

import java.net.URLDecoder

/**
 * 실측 스크립트가 adb로 넘기는 글자 인자. Windows PowerShell → adb shell을 거치면 한글이 깨지므로
 * `u:` 뒤에 퍼센트 인코딩(UTF-8)으로 넘길 수 있다. 예: `--es keyword u:%EC%84%9C%EC%9A%B8%EC%97%AD`(서울역).
 */
object AdbArg {
    fun text(raw: String?): String? =
        if (raw != null && raw.startsWith("u:")) URLDecoder.decode(raw.substring(2), "UTF-8") else raw
}
