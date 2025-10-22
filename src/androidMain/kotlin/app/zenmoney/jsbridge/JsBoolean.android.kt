package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsBoolean : JsValue {
    actual fun toBoolean(): Boolean
}

actual sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

internal class JsBooleanImpl(
    context: JsContext,
    v8Value: Boolean,
) : JsValueImpl(context, v8Value),
    JsBoolean {
    override fun hashCode(): Int = toBoolean().hashCode()

    override fun equals(other: Any?): Boolean = other is JsBooleanImpl && toBoolean() == other.toBoolean()

    override fun toBoolean(): Boolean = v8Value as Boolean
}

internal class JsBooleanObjectImpl(
    context: JsContext,
    v8Value: V8Object,
) : JsObjectImpl(context, v8Value),
    JsBooleanObject {
    private val value: Boolean = v8Value.toString() == "true"

    override fun hashCode(): Int = value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsBooleanObjectImpl && value == other.value

    override fun toBoolean(): Boolean = value
}
