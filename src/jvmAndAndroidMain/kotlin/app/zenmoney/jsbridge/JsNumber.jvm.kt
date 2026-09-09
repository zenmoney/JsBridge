package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueNumber
import com.caoccao.javet.values.reference.V8ValueDoubleObject
import com.caoccao.javet.values.reference.V8ValueIntegerObject

actual sealed interface JsNumber : JsValue {
    actual fun toNumber(): Number
}

actual sealed interface JsNumberObject :
    JsObject,
    JsNumber

internal class JsNumberImpl(
    context: JsContext,
    v8Value: V8ValueNumber<*>,
) : JsValueImpl(context, v8Value),
    JsNumber {
    override fun hashCode(): Int = toNumber().hashCode()

    override fun equals(other: Any?): Boolean =
        other is JsNumber &&
            other !is JsObject &&
            context === other.context &&
            toNumber() == other.toNumber()

    override fun toNumber(): Number = ((v8Value as V8ValueNumber<*>).value as Number).toDouble()
}

internal class JsNumberObjectImpl :
    JsObjectImpl,
    JsNumberObject {
    private val value: Number

    constructor(
        context: JsContext,
        v8Value: V8ValueIntegerObject,
    ) : super(context, v8Value) {
        value = v8Value.valueOf().use { it.asDouble() }
    }

    constructor(
        context: JsContext,
        v8Value: V8ValueDoubleObject,
    ) : super(context, v8Value) {
        value = v8Value.valueOf().use { it.asDouble() }
    }

    override fun toNumber(): Number = value
}
