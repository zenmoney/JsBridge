package app.zenmoney.jsbridge

expect sealed interface JsNumber : JsValue {
    fun toNumber(): Number
}

expect sealed interface JsNumberObject :
    JsObject,
    JsNumber

expect fun JsNumber(
    context: JsContext,
    value: Number,
): JsNumber

expect fun JsNumberObject(
    context: JsContext,
    value: Number,
): JsNumberObject
