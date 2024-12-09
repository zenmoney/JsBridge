package app.zenmoney.jsbridge

expect sealed interface JsBoolean : JsValue {
    fun toBoolean(): Boolean
}

expect sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

expect fun JsBoolean(
    context: JsContext,
    value: Boolean,
): JsBoolean

expect fun JsBooleanObject(
    context: JsContext,
    value: Boolean,
): JsBooleanObject
