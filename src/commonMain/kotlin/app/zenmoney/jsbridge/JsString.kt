package app.zenmoney.jsbridge

expect sealed interface JsString : JsValue

expect sealed interface JsStringObject :
    JsObject,
    JsString

internal expect fun JsString(
    context: JsContext,
    value: String,
): JsString

internal expect fun JsStringObject(
    context: JsContext,
    value: String,
): JsStringObject

fun JsScope.JsString(value: String): JsString = JsString(context, value).autoClose()

fun JsScope.JsStringObject(value: String): JsStringObject = JsStringObject(context, value).autoClose()
