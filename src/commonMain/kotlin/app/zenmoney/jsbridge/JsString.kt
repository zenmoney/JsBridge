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

fun JsScope.JsString(value: String): JsString = JsString(context, value).autoClose()

fun JsScope.JsStringObject(value: String): JsStringObject = JsStringObject(context, value).autoClose()
