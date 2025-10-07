package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsNumber : JsValue {
    actual fun toNumber(): Number
}

actual sealed interface JsNumberObject :
    JsObject,
    JsNumber

actual fun JsNumber(
    context: JsContext,
    value: Number,
): JsNumber = JsValue(context, value) as JsNumber

actual fun JsNumberObject(
    context: JsContext,
    value: Number,
): JsNumberObject = context.evaluateScript("new Number($value)") as JsNumberObject

internal class JsNumberImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsNumber {
    override fun hashCode(): Int = toNumber().hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberImpl && toNumber() == other.toNumber()

    override fun toNumber(): Number = jsValue.toDouble()
}

internal class JsNumberObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsNumberObject {
    override fun hashCode(): Int = toNumber().hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberObjectImpl && toNumber() == other.toNumber()

    override fun toNumber(): Number = jsValue.toDouble()
}
