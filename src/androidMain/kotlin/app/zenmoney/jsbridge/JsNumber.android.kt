package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsNumber : JsValue {
    actual fun toNumber(): Number
}

actual sealed interface JsNumberObject :
    JsObject,
    JsNumber

internal actual fun JsNumber(
    context: JsContext,
    value: Number,
): JsNumber = JsValue(context, value) as JsNumber

internal actual fun JsNumberObject(
    context: JsContext,
    value: Number,
): JsNumberObject = context.createNumberObject.apply(context.globalThis, listOf(JsNumber(context, value))) as JsNumberObject

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

    override fun hashCode(): Int = value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberObjectImpl && value == other.value

    override fun toNumber(): Number = value
}
