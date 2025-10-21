package app.zenmoney.jsbridge

expect class JsContext : AutoCloseable {
    internal val core: JsContextCore

    val globalThis: JsObject
    val NULL: JsValue
    val UNDEFINED: JsValue

    var getPlainValueOf: (JsValue) -> Any?

    constructor()

    @Throws(JsException::class)
    internal fun evaluateScript(script: String): JsValue

    internal fun closeValue(value: JsValue)

    override fun close()
}

internal class JsContextCore : AutoCloseable {
    private var _scope: JsScope? = JsScope()
    var scopeValuesPool: MutableList<ArrayList<AutoCloseable>>? = arrayListOf()

    val scope: JsScope
        get() = _scope ?: throw JsException("JsContext is already closed")

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
