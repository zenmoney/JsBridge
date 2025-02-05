package app.zenmoney.jsbridge

import com.caoccao.javet.values.IV8ValuePrimitiveObject
import com.caoccao.javet.values.primitive.V8ValueBigNumber
import com.caoccao.javet.values.primitive.V8ValueNumber
import com.caoccao.javet.values.reference.V8ValueDoubleObject
import com.caoccao.javet.values.reference.V8ValueIntegerObject
import com.caoccao.javet.values.reference.V8ValueLongObject

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
): JsNumberObject =
    when (value) {
        is Int -> JsNumberObjectImpl(context, context.v8Runtime.createV8ValueIntegerObject(value))
        is Long -> JsNumberObjectImpl(context, context.v8Runtime.createV8ValueLongObject(value))
        else -> JsNumberObjectImpl(context, context.v8Runtime.createV8ValueDoubleObject(value.toDouble()))
    }.also { context.registerValue(it) }

internal class JsNumberImpl :
    JsValueImpl,
    JsNumber {
    constructor(
        context: JsContext,
        v8Value: V8ValueNumber<*>,
    ) : super(context, v8Value)

    constructor(
        context: JsContext,
        v8Value: V8ValueBigNumber<*>,
    ) : super(context, v8Value)

    override fun hashCode(): Int = toNumber().hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberImpl && toNumber() == other.toNumber()

    override fun toNumber(): Number =
        (((v8Value as? V8ValueNumber<*>)?.value ?: (v8Value as V8ValueBigNumber<*>).value) as Number).toDouble()
}

internal class JsNumberObjectImpl :
    JsObjectImpl,
    JsNumberObject {
    private val value: Number =
        run {
            @Suppress("UNCHECKED_CAST")
            val v8PrimitiveValue = (v8Value as IV8ValuePrimitiveObject<V8ValueNumber<*>>).valueOf()
            (v8PrimitiveValue.value.also { v8PrimitiveValue.close() } as Number).toDouble()
        }

    constructor(
        context: JsContext,
        v8Value: V8ValueIntegerObject,
    ) : super(context, v8Value)

    constructor(
        context: JsContext,
        v8Value: V8ValueLongObject,
    ) : super(context, v8Value)

    constructor(
        context: JsContext,
        v8Value: V8ValueDoubleObject,
    ) : super(context, v8Value)

    override fun hashCode(): Int = value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsNumberObjectImpl && value == other.value

    override fun toNumber(): Number = value
}
