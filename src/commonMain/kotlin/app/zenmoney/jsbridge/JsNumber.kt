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

context(scope: JsScope)
fun JsNumber(value: Number): JsNumber = JsNumber(scope.context, value).autoClose()

context(scope: JsScope)
fun JsNumberObject(value: Number): JsNumberObject = JsNumberObject(scope.context, value).autoClose()
