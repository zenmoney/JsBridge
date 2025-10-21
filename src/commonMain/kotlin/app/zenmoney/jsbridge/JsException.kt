package app.zenmoney.jsbridge

class JsException(
    message: String,
    cause: Throwable? = null,
    val data: Map<String, Any?> = emptyMap(),
) : Exception(message, cause)

expect fun JsException(value: JsValue): JsException

@Suppress("FunctionName")
internal expect fun JsError(
    context: JsContext,
    exception: Throwable,
): JsObject

fun JsScope.JsObject(exception: Throwable): JsObject = JsError(context, exception).autoClose()
