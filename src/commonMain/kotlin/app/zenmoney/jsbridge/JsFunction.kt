package app.zenmoney.jsbridge

expect sealed interface JsFunction : JsObject

@Throws(JsException::class)
internal fun JsFunction.call(
    args: List<JsValue> = emptyList(),
    thiz: JsValue = context.globalThis,
): JsValue = context.callFunction(this, args, thiz)

@Throws(JsException::class)
internal fun JsFunction.callAsConstructor(args: List<JsValue> = emptyList()): JsValue = context.callFunctionAsConstructor(this, args)

internal fun JsFunction(
    context: JsContext,
    value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue,
): JsFunction = context.createFunction(value)

fun JsScope.JsFunction(value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue): JsFunction = JsFunction(context, value).autoClose()
