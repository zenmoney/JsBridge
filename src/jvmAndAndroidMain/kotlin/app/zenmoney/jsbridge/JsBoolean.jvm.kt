package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.reference.V8ValueBooleanObject

actual sealed interface JsBoolean : JsValue {
    actual fun toBoolean(): Boolean
}

actual sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

actual fun JsBoolean(
    context: JsContext,
    value: Boolean,
): JsBoolean = JsValue(context, value) as JsBoolean

actual fun JsBooleanObject(
    context: JsContext,
    value: Boolean,
): JsBooleanObject =
    JsBooleanObjectImpl(
        context,
        context.v8Runtime.createV8ValueBooleanObject(value),
    ).also { context.registerValue(it) }

internal class JsBooleanImpl(
    context: JsContext,
    v8Value: V8ValueBoolean,
) : JsValueImpl(context, v8Value),
    JsBoolean {
    override fun hashCode(): Int = toBoolean().hashCode()

    override fun equals(other: Any?): Boolean = other is JsBooleanImpl && toBoolean() == other.toBoolean()

    override fun toBoolean(): Boolean = (v8Value as V8ValueBoolean).value
}

internal class JsBooleanObjectImpl(
    context: JsContext,
    v8Value: V8ValueBooleanObject,
) : JsObjectImpl(context, v8Value),
    JsBooleanObject {
    private val value: Boolean =
        run {
            val v8PrimitiveValue = v8Value.valueOf()
            v8PrimitiveValue.value.also { v8PrimitiveValue.close() }
        }

    override fun hashCode(): Int = value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsBooleanObjectImpl && value == other.value

    override fun toBoolean(): Boolean = value
}
