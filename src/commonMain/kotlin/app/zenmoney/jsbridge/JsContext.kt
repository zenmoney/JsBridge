package app.zenmoney.jsbridge

expect class JsContext : AutoCloseable {
    internal val core: JsContextCore

    var getPlainValueOf: (JsValue) -> Any?

    val globalThis: JsObject

    internal val NULL: JsNull
    internal val UNDEFINED: JsUndefined

    constructor()

    @Throws(JsException::class)
    internal fun evaluateScript(script: String): JsValue

    @Throws(JsException::class)
    internal fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    @Throws(JsException::class)
    internal fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    internal fun createArray(value: Iterable<JsValue>): JsArray

    internal fun createBoolean(value: Boolean): JsBoolean

    internal fun createBooleanObject(value: Boolean): JsBooleanObject

    internal fun createDate(millis: Long): JsDate

    internal fun createError(exception: Throwable): JsObject

    internal fun createException(error: JsValue): JsException

    internal fun createFunction(value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue): JsFunction

    internal fun createNumber(value: Number): JsNumber

    internal fun createNumberObject(value: Number): JsNumberObject

    internal fun createObject(): JsObject

    internal fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    internal fun createString(value: String): JsString

    internal fun createStringObject(value: String): JsStringObject

    internal fun createUint8Array(value: ByteArray): JsUint8Array

    internal fun <T : JsValue> createValueAlias(value: T): T

    internal fun closeValue(value: JsValue)

    override fun close()
}

internal class JsContextCore : AutoCloseable {
    private var _scope: JsScope? = JsScope()
    var scopeValuesPool: MutableList<ArrayList<AutoCloseable>>? = arrayListOf()

    val scope: JsScope
        get() = checkNotNull(_scope) { "JsContext is already closed" }

    fun addValue(value: JsValue) {
        scope.tryAutoClose(value)
    }

    fun removeValue(value: JsValue) {
        _scope?.tryEscape(value)
    }

    override fun close() {
        scopeValuesPool = null
        _scope.also { _scope = null }?.close()
    }
}
