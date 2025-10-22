package app.zenmoney.jsbridge

expect sealed interface JsNumber : JsValue {
    fun toNumber(): Number
}

expect sealed interface JsNumberObject :
    JsObject,
    JsNumber

internal fun JsNumber(
    context: JsContext,
    value: Number,
): JsNumber = context.createNumber(value)

internal fun JsNumberObject(
    context: JsContext,
    value: Number,
): JsNumberObject = context.createNumberObject(value)

fun JsScope.JsNumber(value: Number): JsNumber = JsNumber(context, value).autoClose()

fun JsScope.JsNumberObject(value: Number): JsNumberObject = JsNumberObject(context, value).autoClose()
