package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsNumber : JsValue {
    actual fun toNumber(): Number
}

actual sealed interface JsNumberObject :
    JsObject,
    JsNumber

internal class JsNumberImpl(
    context: JsContext,
    value: Double,
) : JsValueImpl(context, value),
    JsNumber {
    override fun hashCode(): Int = toNumber().hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberImpl && toNumber() == other.toNumber()

    override fun toNumber(): Number = v8Value as Number
}

internal class JsNumberObjectImpl(
    context: JsContext,
    value: V8Object,
) : JsObjectImpl(context, value),
    JsNumberObject {
    private val value: Double = value.toString().toDouble()

    override fun toNumber(): Number = value
}
