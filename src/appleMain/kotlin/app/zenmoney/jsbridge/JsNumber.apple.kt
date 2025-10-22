package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsNumber : JsValue {
    actual fun toNumber(): Number
}

actual sealed interface JsNumberObject :
    JsObject,
    JsNumber

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
