package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsBoolean : JsValue {
    actual fun toBoolean(): Boolean
}

actual sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

internal actual fun JsBoolean(
    context: JsContext,
    value: Boolean,
): JsBoolean = JsValue(context, value) as JsBoolean

internal actual fun JsBooleanObject(
    context: JsContext,
    value: Boolean,
): JsBooleanObject = context.evaluateScript("new Boolean($value)") as JsBooleanObject

internal class JsBooleanImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsBoolean {
    override fun toBoolean(): Boolean = jsValue.toBool()
}

internal class JsBooleanObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsBooleanObject {
    override fun hashCode(): Int = toBoolean().hashCode()

    override fun equals(other: Any?): Boolean = other is JsBooleanObjectImpl && toBoolean() == other.toBoolean()

    override fun toBoolean(): Boolean = jsValue.toBool()
}
