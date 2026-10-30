package app.nextstop.platform

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 타입이 확정되지 않은 Pleos API(예: Odometer 메서드 이름)를 실측하기 위한 리플렉션 도구.
 * 실측으로 이름·시그니처가 확인되면 typed 호출로 교체한다.
 *
 * Pleos Vehicle SDK의 get/check 메서드는 결과를 onComplete/onFailed 람다(kotlin FunctionN)로 돌려준다(공식 샘플 기준).
 */
object Reflective {

    data class MethodInfo(val name: String, val params: List<String>, val returns: String) {
        override fun toString() = "$name(${params.joinToString()}): $returns"
    }

    fun describe(target: Any): List<MethodInfo> =
        target.javaClass.methods
            .filter { Modifier.isPublic(it.modifiers) && it.declaringClass != Any::class.java }
            .map { m -> MethodInfo(m.name, m.parameterTypes.map { it.simpleName }, m.returnType.simpleName) }
            .sortedBy { it.toString() }
            .distinct()

    /** 파라미터가 전부 콜백(FunctionN)인 메서드만 자동 호출 대상. */
    fun isCallbackOnly(m: Method): Boolean =
        m.parameterTypes.isNotEmpty() && m.parameterTypes.all { isFunctionType(it) }

    /** 파라미터가 리스너 인터페이스 하나뿐인 register 메서드. */
    fun isListenerOnly(m: Method): Boolean =
        m.parameterTypes.size == 1 && m.parameterTypes[0].isInterface && !isFunctionType(m.parameterTypes[0])

    private fun isFunctionType(c: Class<*>) = c.name.startsWith("kotlin.jvm.functions.Function")

    /**
     * 콜백만 받는 메서드를 호출하고 결과를 [onResult]로 넘긴다.
     * 파라미터 이름을 알 수 없으므로 순서로 구분한다(관례: 앞=onComplete, 뒤=onFailed). 받은 값이 Throwable이면 실패로 표시.
     */
    fun invokeWithCallbacks(target: Any, m: Method, onResult: (callbackIndex: Int, values: List<Any?>) -> Unit): Throwable? {
        val args = m.parameterTypes.mapIndexed { index, type -> callback(type, index, onResult) }.toTypedArray()
        return try {
            m.invoke(target, *args)
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        } catch (t: Throwable) {
            t
        }
    }

    fun proxyListener(type: Class<*>, onCall: (method: String, args: List<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Probe(${type.simpleName})"
                else -> {
                    onCall(method.name, args?.toList().orEmpty())
                    null
                }
            }
        }

    private fun callback(type: Class<*>, index: Int, onResult: (Int, List<Any?>) -> Unit): Any =
        when (type.name) {
            "kotlin.jvm.functions.Function0" -> {
                val f: () -> Unit = { onResult(index, emptyList()) }
                f
            }
            "kotlin.jvm.functions.Function1" -> {
                val f: (Any?) -> Unit = { a -> onResult(index, listOf(a)) }
                f
            }
            "kotlin.jvm.functions.Function2" -> {
                val f: (Any?, Any?) -> Unit = { a, b -> onResult(index, listOf(a, b)) }
                f
            }
            "kotlin.jvm.functions.Function3" -> {
                val f: (Any?, Any?, Any?) -> Unit = { a, b, c -> onResult(index, listOf(a, b, c)) }
                f
            }
            else -> error("unsupported callback type ${type.name}")
        }
}
