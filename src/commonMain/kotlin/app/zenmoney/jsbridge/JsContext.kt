package app.zenmoney.jsbridge

expect class JsContext : AutoCloseable {
    val globalObject: JsObject
    val NULL: JsValue
    val UNDEFINED: JsValue
    constructor()

    @Throws(JsException::class)
    fun evaluateScript(script: String): JsValue
}
