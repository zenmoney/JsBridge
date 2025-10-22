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

fun JsScope.JsBoolean(value: Boolean): JsBoolean = JsBoolean(context, value).autoClose()

fun JsScope.JsBooleanObject(value: Boolean): JsBooleanObject = JsBooleanObject(context, value).autoClose()
