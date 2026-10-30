package app.nextstop.platform

/**
 * Vehicle SDK 오류를 사람이 읽을 문장으로.
 *
 * 공식 문서는 onFailed의 예외가 VehicleException이면 errorCode(ErrorCode enum)를 보라고 하지만,
 * SDK 2.3.2 aar에서는 그 클래스가 난독화돼 있다(callback.exception.a, ErrorCode를 반환하는 무인자 메서드 보유).
 * 그래서 클래스 이름 대신 "ErrorCode를 반환하는 메서드"를 찾아 코드를 읽는다.
 */
object VehicleErrors {
    private const val ERROR_CODE_CLASS = "ai.pleos.playground.vehicle.constant.ErrorCode"

    fun code(e: Throwable): String? = runCatching {
        e.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && it.returnType.name == ERROR_CODE_CLASS }
            ?.invoke(e)
            ?.toString()
    }.getOrNull()

    fun describe(e: Throwable): String {
        val code = code(e)
        val hint = when (code) {
            "ERROR_SECURITY_PERMISSION" -> "매니페스트 권한 확인"
            "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA" -> "이 차량/에뮬레이터에서 미지원"
            "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> "Vehicle Service 연결 재시도 필요"
            "ERROR_DRIVING_RESTRICTED" -> "주행 중 제한"
            else -> null
        }
        val base = e.message ?: e.javaClass.simpleName
        return listOfNotNull(code, base, hint).joinToString(" · ")
    }
}
