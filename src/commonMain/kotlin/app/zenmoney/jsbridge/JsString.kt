package app.zenmoney.jsbridge

expect sealed interface JsString : JsValue

expect sealed interface JsStringObject :
    JsObject,
    JsString

expect fun JsString(
    context: JsContext,
    value: String,
): JsString

expect fun JsStringObject(
    context: JsContext,
    value: String,
): JsStringObject
