package app.zenmoney.jsbridge

actual fun JsValue.toJsException(): JsException = context.createJsException(this)

actual fun Throwable.toJsObject(context: JsContext): JsObject = context.createJsError(this)
