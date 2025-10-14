package app.zenmoney.jsbridge

class JsException(
    message: String,
    cause: Throwable?,
    val data: Map<String, Any?>,
) : Exception(message, cause)

expect fun JsValue.toJsException(): JsException

expect fun Throwable.toJsObject(context: JsContext): JsObject
