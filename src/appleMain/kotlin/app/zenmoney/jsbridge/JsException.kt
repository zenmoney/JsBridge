package app.zenmoney.jsbridge

actual fun JsValue.toJsException(): JsException = context.createJsException((this as JsValueImpl).jsValue)

actual fun Throwable.toJsObject(context: JsContext): JsObject = JsObjectImpl(context, context.createJsError(this))
