package app.zenmoney.jsbridge

class JsException(
    message: String,
    cause: Throwable? = null,
    val data: Map<String, Any?> = emptyMap(),
    val name: String = "",
) : Exception(message, cause)

fun JsException(value: JsValue): JsException = value.context.createException(value)

@Suppress("FunctionName")
internal fun JsError(
    context: JsContext,
    exception: Throwable,
): JsObject = context.createError(exception)

context(scope: JsScope)
fun JsObject(exception: Throwable): JsObject = JsError(scope.context, exception).autoClose()
