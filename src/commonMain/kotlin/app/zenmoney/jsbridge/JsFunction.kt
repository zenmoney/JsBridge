package app.zenmoney.jsbridge

expect sealed interface JsFunction : JsObject {
    @Throws(JsException::class)
    fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue
}

expect fun JsFunction(
    context: JsContext,
    value: JsObject.(args: List<JsValue>) -> JsValue,
): JsFunction
