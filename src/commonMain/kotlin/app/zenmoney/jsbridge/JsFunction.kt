package app.zenmoney.jsbridge

expect sealed interface JsFunction : JsObject

@Throws(JsException::class)
internal expect fun JsFunction.apply(
    thiz: JsValue,
    args: List<JsValue>,
): JsValue

@Throws(JsException::class)
internal expect fun JsFunction.applyAsConstructor(args: List<JsValue>): JsValue

internal expect fun JsFunction(
    context: JsContext,
    value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue,
): JsFunction

fun JsScope.JsFunction(value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue): JsFunction = JsFunction(context, value).autoClose()
