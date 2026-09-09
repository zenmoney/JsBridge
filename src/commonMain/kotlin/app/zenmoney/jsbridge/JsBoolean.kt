package app.zenmoney.jsbridge

expect sealed interface JsBoolean : JsValue {
    fun toBoolean(): Boolean
}

expect sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

internal fun JsBoolean(
    context: JsContext,
    value: Boolean,
): JsBoolean = context.createBoolean(value)

internal fun JsBooleanObject(
    context: JsContext,
    value: Boolean,
): JsBooleanObject = context.createBooleanObject(value)

context(scope: JsScope)
fun JsBoolean(value: Boolean): JsBoolean = JsBoolean(scope.context, value).autoClose()

context(scope: JsScope)
fun JsBooleanObject(value: Boolean): JsBooleanObject = JsBooleanObject(scope.context, value).autoClose()
