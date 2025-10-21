package app.zenmoney.jsbridge

actual fun JsException(value: JsValue): JsException = value.context.createJsException((value as JsValueImpl).jsValue)

@Suppress("FunctionName")
internal actual fun JsError(
    context: JsContext,
    exception: Throwable,
): JsObject = JsObjectImpl(context, context.createJsError(exception)).also { context.registerValue(it) }
