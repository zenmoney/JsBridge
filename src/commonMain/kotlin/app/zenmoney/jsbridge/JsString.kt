package app.zenmoney.jsbridge

expect sealed interface JsString : JsValue

expect sealed interface JsStringObject :
    JsObject,
    JsString

internal fun JsString(
    context: JsContext,
    value: String,
): JsString = context.createString(value)

internal fun JsStringObject(
    context: JsContext,
    value: String,
): JsStringObject = context.createStringObject(value)

context(scope: JsScope)
fun JsString(value: String): JsString = JsString(scope.context, value).autoClose()

context(scope: JsScope)
fun JsStringObject(value: String): JsStringObject = JsStringObject(scope.context, value).autoClose()
