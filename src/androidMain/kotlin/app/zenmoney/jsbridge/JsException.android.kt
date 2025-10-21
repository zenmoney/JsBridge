package app.zenmoney.jsbridge

actual fun JsException(value: JsValue): JsException = value.context.createJsException(value)

@Suppress("FunctionName")
internal actual fun JsError(
    context: JsContext,
    exception: Throwable,
): JsObject = context.createJsError(exception)
